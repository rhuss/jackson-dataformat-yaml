package com.fasterxml.jackson.dataformat.yaml;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.*;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.base.ParserBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.JsonMappingException;

import org.yaml.snakeyaml.resolver.Resolver;

/**
 * {@link JsonParser} implementation used to expose YAML documents
 * in form that allows other Jackson functionality to process YAML content,
 * such as binding POJOs to and from it, and building tree representations.
 */
public class YAMLParser extends ParserBase
{
    /**
     * Enumeration that defines all togglable features for YAML parsers.
     */
    public enum Feature // implements FormatFeature // in 2.7
    {
        ;

        final boolean _defaultState;
        final int _mask;
        
        /**
         * Method that calculates bit set (flags) of all features that
         * are enabled by default.
         */
        public static int collectDefaults()
        {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }
        
        private Feature(boolean defaultState) {
            _defaultState = defaultState;
            _mask = (1 << ordinal());
        }
        
        public boolean enabledByDefault() { return _defaultState; }
        public boolean enabledIn(int flags) { return (flags & _mask) != 0; }        
        public int getMask() { return _mask; }
    }

    // note: does NOT include '0', handled separately
//    private final static Pattern PATTERN_INT = Pattern.compile("-?[1-9][0-9]*");

    /**
     * We will use pattern that is bit stricter than YAML definition,
     * but we will still allow things like extra '_' in there.
     */
    private final static Pattern PATTERN_FLOAT = Pattern.compile(
            "[-+]?([0-9][0-9_]*)?\\.[0-9]*([eE][-+][0-9]+)?");
    
    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */
    
    /**
     * Codec used for data binding when (if) requested.
     */
    protected ObjectCodec _objectCodec;

    protected int _formatFeatures;

    /*
    /**********************************************************************
    /* Input sources
    /**********************************************************************
     */

    /**
     * Need to keep track of underlying {@link Reader} to be able to
     * auto-close it (if required to)
     */
    protected final Reader _reader;

    protected final ParserImpl _yamlParser;
    protected final Resolver _yamlResolver = new Resolver();

    /*
    /**********************************************************************
    /* State
    /**********************************************************************
     */

    /**
     * Keep track of the last event read, to get access to Location info
     */
    protected Event _lastEvent;

    /**
     * We need to keep track of text values.
     */
    protected String _textValue;

    /**
     * Let's also have a local copy of the current field name
     */
    protected String _currentFieldName;

    /**
     * Flag that is set when current token was derived from an Alias
     * (reference to another value's anchor)
     * 
     * @since 2.1
     */
    protected boolean _currentIsAlias;

    /**
     * Anchor for the value that parser currently points to: in case of
     * structured types, value whose first token current token is.
     */
    protected String _currentAnchor;
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */
    
    public YAMLParser(IOContext ctxt, BufferRecycler br,
            int parserFeatures, int formatFeatures,
            ObjectCodec codec, Reader reader)
    {
        super(ctxt, parserFeatures);    
        _objectCodec = codec;
        _formatFeatures = formatFeatures;
        _reader = reader;
        _yamlParser = new ParserImpl(new StreamReader(reader));
    }


    @Override
    public ObjectCodec getCodec() {
        return _objectCodec;
    }

    @Override
    public void setCodec(ObjectCodec c) {
        _objectCodec = c;
    }

    /*                                                                                       
    /**********************************************************                              
    /* Extended YAML-specific API
    /**********************************************************                              
     */

    /**
     * Method that can be used to check whether current token was
     * created from YAML Alias token (reference to an anchor).
     * 
     * @since 2.1
     */
    public boolean isCurrentAlias() {
        return _currentIsAlias;
    }

    /**
     * Method that can be used to check if the current token has an
     * associated anchor (id to reference via Alias)
     * 
     * @deprecated Since 2.3 (was added in 2.1) -- use {@link #getObjectId} instead
     */
    @Deprecated
    public String getCurrentAnchor() {
        return _currentAnchor;
    }
    
    /*                                                                                       
    /**********************************************************                              
    /* Versioned                                                                             
    /**********************************************************                              
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************                              
    /* ParserBase method impls
    /**********************************************************                              
     */

    @Override
    protected void _closeInput() throws IOException {
        _reader.close();
    }
    
    /*
    /**********************************************************                              
    /* FormatFeature support
    /**********************************************************                              
     */

    @Override
    public int getFormatFeatures() {
        return _formatFeatures;
    }

    @Override
    public JsonParser overrideFormatFeatures(int values, int mask) {
        _formatFeatures = (_formatFeatures & ~mask) | (values & mask);
        return this;
    }

    /*
    /***************************************************
    /* Public API, configuration
    /***************************************************
     */

    /**
     * Method for enabling specified CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser enable(YAMLParser.Feature f)
    {
        _formatFeatures |= f.getMask();
        return this;
    }

    /**
     * Method for disabling specified  CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser disable(YAMLParser.Feature f)
    {
        _formatFeatures &= ~f.getMask();
        return this;
    }

    /**
     * Method for enabling or disabling specified CSV feature
     * (check {@link Feature} for list of features)
     */
    public JsonParser configure(YAMLParser.Feature f, boolean state)
    {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
        return this;
    }

    /**
     * Method for checking whether specified CSV {@link Feature}
     * is enabled.
     */
    public boolean isEnabled(YAMLParser.Feature f) {
        return (_formatFeatures & f.getMask()) != 0;
    }

//    @Override public CsvSchema getSchema() 
    
    /*
    /**********************************************************
    /* Location info
    /**********************************************************
     */

    @Override
    public JsonLocation getTokenLocation()
    {
        if (_lastEvent == null) {
            return JsonLocation.NA;
        }
        return _locationFor(_lastEvent.getStartMark());
    }

    @Override
    public JsonLocation getCurrentLocation() {
        // can assume we are at the end of token now...
        if (_lastEvent == null) {
            return JsonLocation.NA;
        }
        return _locationFor(_lastEvent.getEndMark());
    }
    
    protected JsonLocation _locationFor(Mark m)
    {
        if (m == null) {
            return new JsonLocation(_ioContext.getSourceReference(),
                    -1, -1, -1);
        }
        return new JsonLocation(_ioContext.getSourceReference(),
                -1,
                m.getLine() + 1, // from 0- to 1-based
                m.getColumn() + 1); // ditto
    }

    // Note: SHOULD override 'getTokenLineNr', 'getTokenColumnNr', but those are final in 2.0
    
    /*
    /**********************************************************
    /* Parsing
    /**********************************************************
     */
    
    @Override
    public JsonToken nextToken() throws IOException
    {
        _currentIsAlias = false;
        _binaryValue = null;
        _currentAnchor = null;
        if (_closed) {
            return null;
        }
        
        while (true) {
            Event evt;
            try {
                evt = _yamlParser.getEvent();
            } catch (YAMLException e) {
                /* 12-Mar-2016, tatu: It may look weird that we do NOT add cause
                 *    as is, but see issue [dataformat-yaml#31] for details: basically,
                 *    exposing a SnakeYAML type is leakage that can have nasty side effects
                 */
                JsonParseException e2 = new JsonParseException(this,
                        "YAML decoding problem: "+e.getMessage());
                // try to retain stack trace, however, for troubleshooting
                e2.setStackTrace(e.getStackTrace());
                throw e2;
            }
            // is null ok? Assume it is, for now, consider to be same as end-of-doc
            if (evt == null) {
                return (_currToken = null);
            }
            _lastEvent = evt;
            
            /* One complication: field names are only inferred from the
             * fact that we are in Object context...
             */
            if (_parsingContext.inObject() && _currToken != JsonToken.FIELD_NAME) {
                if (!evt.is(Event.ID.Scalar)) {
                    // end is fine
                    if (evt.is(Event.ID.MappingEnd)) {
                        if (!_parsingContext.inObject()) { // sanity check is optional, but let's do it for now
                            _reportMismatchedEndMarker('}', ']');
                        }
                        _parsingContext = _parsingContext.getParent();
                        return (_currToken = JsonToken.END_OBJECT);
                    }
                    _reportError("Expected a field name (Scalar value in YAML), got this instead: "+evt);
                }
                ScalarEvent scalar = (ScalarEvent) evt;
                String name = scalar.getValue();
                _currentFieldName = name;
                _parsingContext.setCurrentName(name);
                _currentAnchor = scalar.getAnchor();
                return (_currToken = JsonToken.FIELD_NAME);
            }
            // Ugh. Why not expose id, to be able to Switch?

            // scalar values are probably the commonest:
            if (evt.is(Event.ID.Scalar)) {
                JsonToken t = _decodeScalar((ScalarEvent) evt);
                _currToken = t;
                return t;
            }

            // followed by maps, then arrays
            if (evt.is(Event.ID.MappingStart)) {
                Mark m = evt.getStartMark();
                MappingStartEvent map = (MappingStartEvent) evt;
                _currentAnchor = map.getAnchor();
                _parsingContext = _parsingContext.createChildObjectContext(m.getLine(), m.getColumn());
                return (_currToken = JsonToken.START_OBJECT);
            }
            if (evt.is(Event.ID.MappingEnd)) { // actually error; can not have map-end here
                _reportError("Not expecting END_OBJECT but a value");
            }
            if (evt.is(Event.ID.SequenceStart)) {
                Mark m = evt.getStartMark();
                _currentAnchor = ((NodeEvent)evt).getAnchor();
                _parsingContext = _parsingContext.createChildArrayContext(m.getLine(), m.getColumn());
                return (_currToken = JsonToken.START_ARRAY);
            }
            if (evt.is(Event.ID.SequenceEnd)) {
                if (!_parsingContext.inArray()) { // sanity check is optional, but let's do it for now
                    _reportMismatchedEndMarker(']', '}');
                }
                _parsingContext = _parsingContext.getParent();
                return (_currToken = JsonToken.END_ARRAY);
            }

            // after this, less common tokens:
            
            if (evt.is(Event.ID.DocumentEnd)) {
                // logical end of doc; fine. Two choices; either skip, or
                // return null as marker. Do latter, for now. But do NOT close.
                return (_currToken = null);
            }
            if (evt.is(Event.ID.DocumentStart)) {
//                DocumentStartEvent dd = (DocumentStartEvent) evt;
                // does this matter? Shouldn't, should it?
                continue;
            }
            if (evt.is(Event.ID.Alias)) {
                AliasEvent alias = (AliasEvent) evt;
                _currentIsAlias = true;
                _textValue = alias.getAnchor();
                // for now, nothing to do: in future, maybe try to expose as ObjectIds?
                return (_currToken = JsonToken.VALUE_STRING);
            }
            if (evt.is(Event.ID.StreamEnd)) { // end-of-input; force closure
                close();
                return (_currToken = null);
            }
            if (evt.is(Event.ID.StreamStart)) { // useless, skip
                continue;
            }
        }
    }
    
    protected JsonToken _decodeScalar(ScalarEvent scalar)
    {
        String value = scalar.getValue();
        _textValue = value;
        // we may get an explicit tag, if so, use for corroborating...
        String typeTag = scalar.getTag();
        final int len = value.length();

        if (typeTag == null || typeTag.equals("!")) { // no, implicit
            Tag nodeTag = _yamlResolver.resolve(NodeId.scalar, value, scalar.getImplicit().canOmitTagInPlainScalar());
            if (nodeTag == Tag.STR) {
                return (_currToken = JsonToken.VALUE_STRING);
            } else if (nodeTag == Tag.INT) {
                return _decodeNumberScalar(value, len);
            } else if (nodeTag == Tag.FLOAT) {
                _numTypesValid = 0;
                return JsonToken.VALUE_NUMBER_FLOAT;
            } else if (nodeTag == Tag.BOOL) {
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            } else if (nodeTag == Tag.NULL) {
                return JsonToken.VALUE_NULL;
            } else {
                // what to do with timestamp and binary and merge etc.
                return JsonToken.VALUE_STRING;
            }
        } else { // yes, got type tag
            if (typeTag.startsWith("tag:yaml.org,2002:")) {
                typeTag = typeTag.substring("tag:yaml.org,2002:".length());
                if (typeTag.contains(",")) {
                    typeTag = typeTag.split(",")[0];
                }
            }
            // canonical values by YAML are actually 'y' and 'n'; but plenty more unofficial:
            if ("bool".equals(typeTag)) { // must be "true" or "false"
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            } else if ("int".equals(typeTag)) {
                return _decodeNumberScalar(value, len);
            } else if ("float".equals(typeTag)) {
                _numTypesValid = 0;
                return JsonToken.VALUE_NUMBER_FLOAT;
            } else if ("null".equals(typeTag)) {
                return JsonToken.VALUE_NULL;
            }
        }
        
        // any way to figure out actual type? No?
        return JsonToken.VALUE_STRING;
    }

    protected Boolean _matchYAMLBoolean(String value, int len)
    {
        switch (len) {
        case 1:
            switch (value.charAt(0)) {
            case 'y': case 'Y': return Boolean.TRUE;
            case 'n': case 'N': return Boolean.FALSE;
            }
            break;
        case 2:
            if ("no".equalsIgnoreCase(value)) return Boolean.FALSE;
            if ("on".equalsIgnoreCase(value)) return Boolean.TRUE;
            break;
        case 3:
            if ("yes".equalsIgnoreCase(value)) return Boolean.TRUE;
            if ("off".equalsIgnoreCase(value)) return Boolean.FALSE;
            break;
        case 4:
            if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
            break;
        case 5:
            if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            break;
        }
        return null;
    }

    protected JsonToken _decodeNumberScalar(String value, final int len)
    {
        if ("0".equals(value)) { // special case for regexp (can't take minus etc)
            _numberNegative = false;
            _numberInt = 0;
            _numTypesValid = NR_INT;
            return JsonToken.VALUE_NUMBER_INT;
        }
        /* 05-May-2012, tatu: Turns out this is a hot spot; so let's write it
         *   out and avoid regexp overhead...
         */
        //if (PATTERN_INT.matcher(value).matches()) {
        int i;
        if (value.charAt(0) == '-') {
            _numberNegative = true;
            i = 1;
            if (len == 1) {
                return null;
            }
        } else {
            _numberNegative = false;
            i = 0;
        }
        while (true) {
            int c = value.charAt(i);
            if (c > '9' || c < '0') {
                break;
            }
            if (++i == len) {
                _numTypesValid = 0;
                return JsonToken.VALUE_NUMBER_INT;
            }
        }
        if (PATTERN_FLOAT.matcher(value).matches()) {
            _numTypesValid = 0;
            return JsonToken.VALUE_NUMBER_FLOAT;
        }
        return null;
    }   

    /*
    /**********************************************************
    /* String value handling
    /**********************************************************
     */

    // For now we do not store char[] representation...
    @Override
    public boolean hasTextCharacters() {
        return false;
    }
    
    @Override
    public String getText() throws IOException
    {
        if (_currToken == JsonToken.VALUE_STRING) {
            return _textValue;
        }
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        if (_currToken != null) {
            if (_currToken.isScalarValue()) {
                return _textValue;
            }
            return _currToken.asString();
        }
        return null;
    }

    @Override
    public String getCurrentName() throws IOException
    {
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        return super.getCurrentName();
    }

    @Override
    public char[] getTextCharacters() throws IOException {
        String text = getText();
        return (text == null) ? null : text.toCharArray();
    }

    @Override
    public int getTextLength() throws IOException {
        String text = getText();
        return (text == null) ? 0 : text.length();
    }

    @Override
    public int getTextOffset() throws IOException {
        return 0;
    }

    @Override // since 2.8
    public int getText(Writer writer) throws IOException
    {
        String str = getText();
        if (str == null) {
            return 0;
        }
        writer.write(str);
        return str.length();
    }

    /*
    /**********************************************************************
    /* Binary (base64)
    /**********************************************************************
     */

    @Override
    public Object getEmbeddedObject() throws IOException {
        return null;
    }

    // TODO: can remove from 2.9 or so (base impl added in 2.8)
    @SuppressWarnings("resource")
    @Override
    public byte[] getBinaryValue(Base64Variant variant) throws IOException
    {
        if (_binaryValue == null) {
            if (_currToken != JsonToken.VALUE_STRING) {
                _reportError("Current token ("+_currToken+") not VALUE_STRING, can not access as binary");
            }
            ByteArrayBuilder builder = _getByteArrayBuilder();
            _decodeBase64(getText(), builder, variant);
            _binaryValue = builder.toByteArray();
        }
        return _binaryValue;
    }

    /*
    /**********************************************************************
    /* Number accessor overrides
    /**********************************************************************
     */
    
    @Override
    protected void _parseNumericValue(int expType) throws IOException
    {
        // Int or float?
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _textValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numberInt = Integer.parseInt(_textValue);
                _numTypesValid = NR_INT;
                return;
            }
            if (len <= 18) { // definitely fits AND is easy to parse using 2 int parse calls
                long l = Long.parseLong(_textValue);
                // [JACKSON-230] Could still fit in int, need to check
                if (len == 10) {
                    if (_numberNegative) {
                        if (l >= Integer.MIN_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    } else {
                        if (l <= Integer.MAX_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    }
                }
                _numberLong = l;
                _numTypesValid = NR_LONG;
                return;
            }
            // !!! TODO: implement proper bounds checks; now we'll just use BigInteger for convenience
            try {
                BigInteger n = new BigInteger(_textValue);
                // Could still fit in a long, need to check
                if (len == 19 && n.bitLength() <= 63) {
                    _numberLong = n.longValue();
                    _numTypesValid = NR_LONG;
                    return;
                }
                _numberBigInt = n;
                _numTypesValid = NR_BIGINT;
                return;
            } catch (NumberFormatException nex) {
                // Can this ever occur? Due to overflow, maybe?
                _wrapError("Malformed numeric value '"+_textValue+"'", nex);
            }
        }
        if (_currToken == JsonToken.VALUE_NUMBER_FLOAT) {
            // related to [Issue-4]: strip out optional underscores, if any:
            String str = _cleanYamlDouble(_textValue);
            try {
                if (expType == NR_BIGDECIMAL) {
                    _numberBigDecimal = new BigDecimal(str);
                    _numTypesValid = NR_BIGDECIMAL;
                } else {
                    // Otherwise double has to do
                    _numberDouble = Double.parseDouble(str);
                    _numTypesValid = NR_DOUBLE;
                }
            } catch (NumberFormatException nex) {
                // Can this ever occur? Due to overflow, maybe?
                _wrapError("Malformed numeric value '"+str+"'", nex);
            }
            return;
        }
        _reportError("Current token ("+_currToken+") not numeric, can not use numeric value accessors");
    }

    @Override
    protected int _parseIntValue() throws IOException
    {
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _textValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numTypesValid = NR_INT;
                return (_numberInt = Integer.parseInt(_textValue));
            }
        }
        _parseNumericValue(NR_INT);
        if ((_numTypesValid & NR_INT) == 0) {
            convertNumberToInt();
        }
        return _numberInt;
    }

    /*
    /**********************************************************************
    /* Native id (type id) access
    /**********************************************************************
     */

    @Override
    public boolean canReadObjectId() { // yup
        return true;
    }
    
    @Override
    public boolean canReadTypeId() {
        return true; // yes, YAML got 'em
    }
    
    @Override
    public String getObjectId() throws IOException, JsonGenerationException
    {
        return _currentAnchor;
    }

    @Override
    public String getTypeId() throws IOException, JsonGenerationException
    {
        String tag;
        if (_lastEvent instanceof CollectionStartEvent) {
            tag = ((CollectionStartEvent) _lastEvent).getTag();
        } else if (_lastEvent instanceof ScalarEvent) {
            tag = ((ScalarEvent) _lastEvent).getTag();
        } else {
            return null;
        }
        if (tag != null) {
            /* 04-Aug-2013, tatu: Looks like YAML parser's expose these in...
             *   somewhat exotic ways sometimes. So let's prepare to peel off
             *   some wrappings:
             */
            while (tag.startsWith("!")) {
                tag = tag.substring(1);
            }
            return tag;
        }
        return null;
    }
    
    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */
    
    /**
     * Helper method used to clean up YAML floating-point value so it can be parsed
     * using standard JDK classes.
     * Currently this just means stripping out optional underscores.
     */
    private String _cleanYamlDouble(String str)
    {
        final int len = str.length();
        int ix = str.indexOf('_');
        if (ix < 0 || len == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder(len);
        // first: do we have a leading plus sign to skip?
        int i = (str.charAt(0) == '+') ? 1 : 0;
        for (; i < len; ++i) {
            char c = str.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
