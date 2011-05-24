/**
 * 
 */
package uk.co.jemos.protomak.engine.impl;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import uk.co.jemos.protomak.engine.api.ConversionService;
import uk.co.jemos.protomak.engine.api.XsomComplexTypeProcessor;
import uk.co.jemos.protomak.engine.exceptions.ProtomakXsdToProtoConversionError;
import uk.co.jemos.protomak.engine.utils.ProtomakEngineConstants;
import uk.co.jemos.protomak.engine.utils.ProtomakEngineHelper;
import uk.co.jemos.xsds.protomak.proto.MessageAttributeOptionalType;
import uk.co.jemos.xsds.protomak.proto.MessageAttributeType;
import uk.co.jemos.xsds.protomak.proto.MessageRuntimeType;
import uk.co.jemos.xsds.protomak.proto.MessageType;
import uk.co.jemos.xsds.protomak.proto.ProtoRuntimeType;
import uk.co.jemos.xsds.protomak.proto.ProtoType;

import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.parser.XSOMParser;

/**
 * XSD to Proto conversion service.
 * 
 * <p>
 * The mail goal of this class is to convert a given XSD to one or more proto
 * files.
 * </p>
 * 
 * @author mtedone
 * 
 */
public class XsomXsdToProtoConversionServiceImpl implements ConversionService {

	//------------------->> Constants

	/** The application logger. */
	public static final org.apache.log4j.Logger LOG = org.apache.log4j.Logger
			.getLogger(XsomXsdToProtoConversionServiceImpl.class);

	//------------------->> Instance / Static variables

	private final XsomComplexTypeProcessor complexTypeProcessor;

	//------------------->> Constructors

	/**
	 * Default constructor.
	 */
	public XsomXsdToProtoConversionServiceImpl() {
		this(XsomDefaultComplexTypeProcessor.getInstance());
	}

	/**
	 * Full constructor
	 * 
	 * @param complexTypeProcessor
	 *            The complex type processor
	 */
	public XsomXsdToProtoConversionServiceImpl(XsomComplexTypeProcessor complexTypeProcessor) {
		super();
		this.complexTypeProcessor = complexTypeProcessor;
	}

	//------------------->> Public methods

	/**
	 * {@inheritDoc}
	 * 
	 * @throws IllegalArgumentException
	 *             If the {@code inputPath} does not exist.
	 */
	public void generateProtoFiles(String inputPath, String outputPath) {

		File inputFilePath = new File(inputPath);
		if (!inputFilePath.exists()) {
			String errMsg = "The XSD input file: " + inputFilePath.getAbsolutePath()
					+ " does not exist. Throwing an exception.";
			LOG.error(errMsg);
			throw new IllegalArgumentException(errMsg);
		}

		File protosOutputFolder = new File(outputPath);
		if (!protosOutputFolder.exists()) {
			LOG.info("Output folder: " + outputPath + " does not exist. Creating it.");
			protosOutputFolder.mkdirs();
		}

		ProtoType proto = new ProtoType();

		XsdToProtoErrorHandler errorHandler = new XsdToProtoErrorHandler();
		XsdToProtoEntityResolver entityResolver = new XsdToProtoEntityResolver();

		//Let's start the dance by reading the XSD file
		XSOMParser parser = new XSOMParser();
		parser.setErrorHandler(errorHandler);
		parser.setEntityResolver(entityResolver);

		try {
			parser.parse(inputFilePath);
			XSSchemaSet sset = parser.getResult();
			if (null == sset) {
				throw new IllegalStateException(
						"An error occurred while parsing the schema. Aborting.");
			}

			manageComplexTypes(proto, sset);

			manageElements(proto, sset);

		} catch (SAXException e) {
			throw new ProtomakXsdToProtoConversionError(e);
		} catch (IOException e) {
			throw new ProtomakXsdToProtoConversionError(e);
		}

	}

	// ------------------->> Getters / Setters

	//------------------->> Private methods

	/**
	 * It goes through all complex types in the XSD and for each one it creates
	 * a message in proto.
	 * 
	 * @param proto
	 *            The proto object
	 * @param schema
	 *            The representation of the XSD Schema
	 */
	private void manageComplexTypes(ProtoType proto, XSSchemaSet schema) {

		List<MessageType> protoMessages = proto.getMessage();

		Iterator<XSComplexType> complexTypesIterator = schema.iterateComplexTypes();

		XSComplexType complexType = null;

		String packageName = null;

		while (complexTypesIterator.hasNext()) {

			complexType = complexTypesIterator.next();
			if (complexType.getName().equals(ProtomakEngineConstants.ANY_TYPE_NAME)) {
				LOG.debug("Skipping anyType: " + complexType.getName());
				continue;
			}
			if (null == packageName) {
				packageName = complexType.getTargetNamespace();
				LOG.info("Proto package will be: " + packageName);
				proto.setPackage(packageName);
			}

			MessageType messageType = complexTypeProcessor.processComplexType(complexType);
			LOG.info("Retrieved message type: " + messageType);
			protoMessages.add(messageType);
			LOG.info("Proto Type: " + proto);

		}
	}

	/**
	 * It goes through all elements defined in the XSD and for each one it
	 * creates a default message.
	 * 
	 * @param proto
	 *            The root proto object
	 * @param schema
	 *            The XSD schema representation
	 */
	private void manageElements(ProtoType proto, XSSchemaSet schema) {
		//Iterates over the elements
		Iterator<XSElementDecl> declaredElementsIterator = schema.iterateElementDecls();
		int defaultMessageIdx = 1;
		while (declaredElementsIterator.hasNext()) {
			XSElementDecl element = declaredElementsIterator.next();
			MessageType msgType = new MessageType();
			msgType.setName(ProtomakEngineConstants.DEFAULT_MESSAGE_NAME + defaultMessageIdx);
			List<MessageAttributeType> msgAttributes = msgType.getMsgAttribute();
			MessageAttributeType msgAttrType = new MessageAttributeType();
			msgAttrType.setName(element.getName());
			msgAttrType.setIndex(1);//Always one attribute per element
			//For single elements it appears there are no other options than required
			msgAttrType.setOptionality(MessageAttributeOptionalType.REQUIRED);
			XSType elementType = element.getType();
			MessageRuntimeType runtimeType = new MessageRuntimeType();
			ProtoRuntimeType protoRuntimeType = ProtomakEngineHelper.XSD_TO_PROTO_TYPE_MAPPING
					.get(elementType.getName());
			if (null == protoRuntimeType) {
				throw new IllegalStateException(
						"For the XSD type: "
								+ elementType.getName()
								+ " no mapping could be found in ProtomakEngineHelper.XSD_TO_PROTO_TYPE_MAPPING");
			}

			runtimeType.setProtoType(protoRuntimeType);
			msgAttrType.setRuntimeType(runtimeType);
			msgAttributes.add(msgAttrType);

			defaultMessageIdx++;

			proto.getMessage().add(msgType);
		}
	}

	//------------------->> equals() / hashcode() / toString()

	//------------------->> Inner classes

	/**
	 * It handles Schema parsing errors.
	 * 
	 * @author mtedone
	 * 
	 */
	private static class XsdToProtoErrorHandler implements ErrorHandler {

		public void warning(SAXParseException exception) throws SAXException {
			LOG.warn("Warning from XSOM Parser: ", exception);
			throw exception;
		}

		public void error(SAXParseException exception) throws SAXException {
			LOG.error("Error from XSOM Parser: ", exception);
			throw exception;
		}

		public void fatalError(SAXParseException exception) throws SAXException {
			LOG.fatal("Fatal error from XSOM Parser: ", exception);
			throw exception;

		}

	}

	/**
	 * It manages entity resolution.
	 * 
	 * @author mtedone
	 * 
	 */
	private static class XsdToProtoEntityResolver implements EntityResolver {

		public InputSource resolveEntity(String publicId, String systemId) throws SAXException,
				IOException {
			throw new UnsupportedOperationException("Not implemented yet.");
		}

	}

}