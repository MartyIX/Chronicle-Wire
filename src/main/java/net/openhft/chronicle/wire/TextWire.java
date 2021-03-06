/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.bytes.ref.TextIntReference;
import net.openhft.chronicle.bytes.ref.TextLongArrayReference;
import net.openhft.chronicle.bytes.ref.TextLongReference;
import net.openhft.chronicle.bytes.util.Compression;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.pool.ClassLookup;
import net.openhft.chronicle.core.threads.ThreadLocalHelper;
import net.openhft.chronicle.core.util.*;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xerial.snappy.Snappy;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.BufferUnderflowException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.bytes.BytesStore.empty;
import static net.openhft.chronicle.bytes.NativeBytes.nativeBytes;

/**
 * YAML Based wire format
 */
public class TextWire extends AbstractWire implements Wire {

    public static final BytesStore TYPE = BytesStore.from("!type ");
    static final String SEQ_MAP = "!seqmap";
    static final String NULL = "!null \"\"";
    static final BitSet STARTS_QUOTE_CHARS = new BitSet();
    static final BitSet QUOTE_CHARS = new BitSet();
    static final ThreadLocal<WeakReference<StopCharTester>> ESCAPED_QUOTES = new ThreadLocal<>();//ThreadLocal.withInitial(StopCharTesters.QUOTES::escaping);
    static final ThreadLocal<WeakReference<StopCharTester>> ESCAPED_SINGLE_QUOTES = new ThreadLocal<>();//ThreadLocal.withInitial(() -> StopCharTesters.SINGLE_QUOTES.escaping());
    static final ThreadLocal<WeakReference<StopCharTester>> ESCAPED_END_OF_TEXT = new ThreadLocal<>();// ThreadLocal.withInitial(() -> TextStopCharsTesters.END_OF_TEXT.escaping());
    static final ThreadLocal<WeakReference<StopCharsTester>> STRICT_ESCAPED_END_OF_TEXT = new ThreadLocal<>();// ThreadLocal.withInitial(() -> TextStopCharsTesters.END_OF_TEXT.escaping());

    static final BytesStore COMMA_SPACE = BytesStore.from(", ");
    static final BytesStore COMMA_NEW_LINE = BytesStore.from(",\n");
    static final BytesStore NEW_LINE = BytesStore.from("\n");
    static final BytesStore EMPTY_AFTER_COMMENT = BytesStore.from(""); // not the same as EMPTY so we can check this value.
    static final BytesStore EMPTY = BytesStore.from("");
    static final BytesStore SPACE = BytesStore.from(" ");
    static final BytesStore END_FIELD = NEW_LINE;
    static final char[] HEXADECIMAL = "0123456789ABCDEF".toCharArray();

    static {
        for (char ch : "?0123456789+- ',#:{}[]|>!\\".toCharArray())
            STARTS_QUOTE_CHARS.set(ch);
        for (char ch : "?,#:{}[]|>\\".toCharArray())
            QUOTE_CHARS.set(ch);
        // make sure it has loaded.
        WireInternal.INTERNER.valueCount();
    }

    protected final TextValueOut valueOut = createValueOut();
    protected final TextValueIn valueIn = createValueIn();
    private final StringBuilder sb = new StringBuilder();
    protected long lineStart = 0;
    DefaultValueIn defaultValueIn;
    private WriteDocumentContext writeContext;
    private ReadDocumentContext readContext;
    private boolean strict = false;
    private boolean addTimeStamps = false;

    public TextWire(@NotNull Bytes bytes, boolean use8bit) {
        super(bytes, use8bit);
    }

    public TextWire(@NotNull Bytes bytes) {
        this(bytes, false);
    }

    @NotNull
    public static TextWire fromFile(String name) throws IOException {
        return new TextWire(BytesUtil.readFile(name), true);
    }

    @NotNull
    public static TextWire from(@NotNull String text) {
        return new TextWire(Bytes.from(text));
    }

    public static String asText(@NotNull Wire wire) {
        assert wire.startUse();
        try {
            long pos = wire.bytes().readPosition();
            @NotNull TextWire tw = new TextWire(nativeBytes());
            wire.copyTo(tw);
            wire.bytes().readPosition(pos);
            return tw.toString();
        } finally {
            assert wire.endUse();
        }
    }

    public static <ACS extends Appendable & CharSequence> void unescape(@NotNull ACS sb) {
        int end = 0;
        int length = sb.length();
        for (int i = 0; i < length; i++) {
            char ch = sb.charAt(i);
            if (ch == '\\' && i < length - 1) {
                char ch3 = sb.charAt(++i);
                switch (ch3) {
                    case '0':
                        ch = 0;
                        break;
                    case 'a':
                        ch = 7;
                        break;
                    case 'b':
                        ch = '\b';
                        break;
                    case 't':
                        ch = '\t';
                        break;
                    case 'n':
                        ch = '\n';
                        break;
                    case 'v':
                        ch = 0xB;
                        break;
                    case 'f':
                        ch = 0xC;
                        break;
                    case 'r':
                        ch = '\r';
                        break;
                    case 'e':
                        ch = 0x1B;
                        break;
                    case 'N':
                        ch = 0x85;
                        break;
                    case '_':
                        ch = 0xA0;
                        break;
                    case 'x':
                        ch = (char)
                                (Character.getNumericValue(sb.charAt(++i)) * 16 +
                                        Character.getNumericValue(sb.charAt(++i)));
                        break;
                    case 'u':
                        ch = (char)
                                (Character.getNumericValue(sb.charAt(++i)) * 4096 +
                                        Character.getNumericValue(sb.charAt(++i)) * 256 +
                                        Character.getNumericValue(sb.charAt(++i)) * 16 +
                                        Character.getNumericValue(sb.charAt(++i)));
                        break;
                    default:
                        ch = ch3;
                }
            }
            AppendableUtil.setCharAt(sb, end++, ch);
        }
        if (length != sb.length())
            throw new IllegalStateException("Length changed from " + length + " to " + sb.length() + " for " + sb);
        AppendableUtil.setLength(sb, end);
    }

    @Nullable
    static StopCharTester getEscapingSingleQuotes() {
        StopCharTester sct = ThreadLocalHelper.getTL(ESCAPED_SINGLE_QUOTES,
                StopCharTesters.SINGLE_QUOTES::escaping);
        // reset it.
        sct.isStopChar(' ');
        return sct;
    }

    public boolean strict() {
        return strict;
    }

    public TextWire strict(boolean strict) {
        this.strict = strict;
        return this;
    }

    public boolean addTimeStamps() {
        return addTimeStamps;
    }

    public TextWire addTimeStamps(boolean addTimeStamps) {
        this.addTimeStamps = addTimeStamps;
        return this;
    }

    @NotNull
    public <T> T methodWriter(@NotNull Class<T> tClass, Class... additional) {
        Class[] interfaces = ObjectUtils.addAll(tClass, additional);

        //noinspection unchecked
        return (T) Proxy.newProxyInstance(tClass.getClassLoader(), interfaces, new TextMethodWriterInvocationHandler(this));
    }

    @NotNull
    public <T> MethodWriterBuilder<T> methodWriterBuilder(@NotNull Class<T> tClass) {
        return new MethodWriterBuilder<>(tClass, new TextMethodWriterInvocationHandler(this));
    }

    @Override
    public void classLookup(ClassLookup classLookup) {
        this.classLookup = classLookup;
    }

    @Override
    public ClassLookup classLookup() {
        return classLookup;
    }

    @NotNull
    @Override
    public DocumentContext writingDocument(boolean metaData) {
        if (writeContext == null)
            writeContext = new WriteDocumentContext(this);
        writeContext.start(metaData);
        return writeContext;
    }

    @NotNull
    @Override
    public DocumentContext readingDocument() {
        initReadContext();
        return readContext;
    }

    protected void initReadContext() {
        if (readContext == null)
            readContext = new TextReadDocumentContext(this);
        readContext.start();
    }

    @NotNull
    public TextWire useBinaryDocuments() {
        readContext = new BinaryReadDocumentContext(this, false);
        return this;
    }

    @NotNull
    public TextWire useTextDocuments() {
        readContext = new TextReadDocumentContext(this);
        return this;
    }

    @NotNull
    @Override
    public DocumentContext readingDocument(long readLocation) {
        final long readPosition = bytes().readPosition();
        final long readLimit = bytes().readLimit();
        bytes().readPosition(readLocation);
        initReadContext();
        readContext.closeReadLimit(readLimit);
        readContext.closeReadPosition(readPosition);
        return readContext;
    }

    @NotNull
    protected TextValueOut createValueOut() {
        return new TextValueOut();
    }

    @NotNull
    protected TextValueIn createValueIn() {
        return new TextValueIn();
    }

    public String toString() {
        if (bytes.readRemaining() > (1024 * 128)) {
            final long l = bytes.readLimit();
            try {
                bytes.readLimit(bytes.readPosition() + (1024 * 128));
                return bytes.toString() + "..";
            } finally {
                bytes.readLimit(l);
            }
        } else
            return bytes.toString();
    }

    @Override
    public void copyTo(@NotNull WireOut wire) {
        wire.bytes().write(bytes, bytes().readPosition(), bytes().readLimit());
    }

    @NotNull
    @Override
    public ValueIn read() {
        readField(acquireStringBuilder());
        return valueIn;
    }

    @NotNull
    private StringBuilder acquireStringBuilder() {
        StringUtils.setCount(sb, 0);
        return sb;
    }

    @NotNull
    protected StringBuilder readField(@NotNull StringBuilder sb) {
        consumePadding();
        try {
            int ch = peekCode();
            // 10xx xxxx, 1111 xxxx
            if (ch > 0x80 && ((ch & 0xC0) == 0x80 || (ch & 0xF0) == 0xF0)) {
                throw new IllegalStateException("Attempting to read binary as TextWire ch=" + Integer.toHexString(ch));
            }
            if (ch == '?') {
                bytes.readSkip(1);
                consumePadding();
                ch = peekCode();

            }
            if (ch == '"') {
                bytes.readSkip(1);

                parseUntil(sb, getEscapingQuotes());

                consumePadding();
                ch = readCode();
                if (ch != ':')
                    throw new UnsupportedOperationException("Expected a : at " + bytes.toDebugString() + " was " + (char) ch);

            } else if (ch == '\'') {
                bytes.readSkip(1);

                parseUntil(sb, getEscapingSingleQuotes());

                consumePadding();
                ch = readCode();
                if (ch != ':')
                    throw new UnsupportedOperationException("Expected a : at " + bytes.toDebugString() + " was " + (char) ch);

            } else if (ch < 0) {
                sb.setLength(0);
                return sb;

            } else {
                parseText(sb);
            }
            unescape(sb);
        } catch (BufferUnderflowException e) {
            Jvm.debug().on(getClass(), e);
        }
        //      consumePadding();
        return sb;
    }

    @Nullable
    @Override
    public <K> K readEvent(@NotNull Class<K> expectedClass) {
        consumePadding(0);
        @NotNull StringBuilder sb = acquireStringBuilder();
        try {
            int ch = peekCode();
            // 10xx xxxx, 1111 xxxx
            if (ch > 0x80 && ((ch & 0xC0) == 0x80 || (ch & 0xF0) == 0xF0)) {
                throw new IllegalStateException("Attempting to read binary as TextWire ch=" + Integer.toHexString(ch));

            } else if (ch == '?') {
                bytes.readSkip(1);
                consumePadding();
                @Nullable final K object = valueIn.object(expectedClass);
                consumePadding();
                int ch2 = readCode();
                if (ch2 != ':')
                    throw new IllegalStateException("Unexpected character after field " + ch + " '" + (char) ch + "'");
                return object;

            } else if (ch == '[') {
                return valueIn.object(expectedClass);

            } else if (ch == '"' || ch == '\'') {
                bytes.readSkip(1);

                final StopCharTester escapingQuotes = ch == '"' ? getEscapingQuotes() : getEscapingSingleQuotes();
                parseUntil(sb, escapingQuotes);

                consumePadding(1);
                ch = readCode();
                if (ch != ':')
                    throw new UnsupportedOperationException("Expected a : at " + bytes.toDebugString());

            } else if (ch < 0) {
                sb.setLength(0);
                return null;

            } else {
                parseText(sb);
            }
            unescape(sb);
        } catch (BufferUnderflowException e) {
            Jvm.debug().on(getClass(), e);
        }
        //      consumePadding();
        return toExpected(expectedClass, sb);
    }

    private void parseText(StringBuilder sb) {
        if (strict)
            parseUntil(sb, getStrictEscapingEndOfText());
        else
            parseUntil(sb, getEscapingEndOfText());
    }

    @Nullable
    private <K> K toExpected(Class<K> expectedClass, StringBuilder sb) {
        return ObjectUtils.convertTo(expectedClass, WireInternal.INTERNER.intern(sb));
    }

    @NotNull
    protected StopCharTester getEscapingEndOfText() {
        StopCharTester escaping = ThreadLocalHelper.getTL(ESCAPED_END_OF_TEXT,
                TextStopCharTesters.END_OF_TEXT::escaping);
        // reset it.
        escaping.isStopChar(' ');
        return escaping;
    }

    @NotNull
    protected StopCharsTester getStrictEscapingEndOfText() {
        StopCharsTester escaping = ThreadLocalHelper.getTL(STRICT_ESCAPED_END_OF_TEXT,
                TextStopCharsTesters.STRICT_END_OF_TEXT::escaping);
        // reset it.
        escaping.isStopChar(' ', ' ');
        return escaping;
    }

    @Nullable
    protected StopCharTester getEscapingQuotes() {
        StopCharTester sct = ThreadLocalHelper.getTL(ESCAPED_QUOTES,
                StopCharTesters.QUOTES::escaping);
        // reset it.
        sct.isStopChar(' ');
        return sct;
    }

    @Override
    public void consumePadding() {
        consumePadding(0);
    }

    @NotNull
    public String readingPeekYaml() {
        throw new UnsupportedOperationException();
    }

    public void consumePadding(int commas) {
        for (; ; ) {
            int codePoint = peekCode();
            if (codePoint == '#') {
                //noinspection StatementWithEmptyBody
                while (readCode() >= ' ') ;
                this.lineStart = bytes.readPosition();
            } else if (codePoint == ',') {
                if (commas-- <= 0)
                    return;
                bytes.readSkip(1);
                if (commas == 0)
                    return;
            } else if (Character.isWhitespace(codePoint)) {
                if (codePoint == '\n' || codePoint == '\r')
                    this.lineStart = bytes.readPosition() + 1;
                bytes.readSkip(1);
            } else {
                break;
            }
        }
    }

    protected void consumeDocumentStart() {
        if (bytes.readRemaining() > 4) {
            long pos = bytes.readPosition();
            if (bytes.readByte(pos) == '-' && bytes.readByte(pos + 1) == '-' && bytes.readByte(pos + 2) == '-')
                bytes.readSkip(3);
        }
    }

    int peekCode() {
        return bytes.peekUnsignedByte();
    }

    /**
     * returns true if the next string is {@code str}
     *
     * @param source string
     * @return true if the strings are the same
     */
    protected boolean peekStringIgnoreCase(@NotNull final String source) {
        if (source.isEmpty())
            return true;

        if (bytes.readRemaining() < 1)
            return false;

        long pos = bytes.readPosition();

        try {
            for (int i = 0; i < source.length(); i++) {
                if (Character.toLowerCase(source.charAt(i)) != Character.toLowerCase(bytes.readByte()))
                    return false;
            }
        } finally {
            bytes.readPosition(pos);
        }

        return true;
    }

    protected int readCode() {
        if (bytes.readRemaining() < 1)
            return -1;
        return bytes.readUnsignedByte();
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull WireKey key) {
        return read(key.name(), key.code(), key.defaultValue());
    }

    private ValueIn read(@NotNull CharSequence keyName, int keyCode, Object defaultValue) {
        consumePadding();
        ValueInState curr = valueIn.curr();
        @NotNull StringBuilder sb = acquireStringBuilder();
        // did we save the position last time
        // so we could go back and parseOne an older field?
        if (curr.savedPosition() > 0) {
            bytes.readPosition(curr.savedPosition() - 1);
            curr.savedPosition(0L);
        }
        while (bytes.readRemaining() > 0) {
            long position = bytes.readPosition();
            // at the current position look for the field.
            readField(sb);
            if (StringUtils.equalsCaseIgnore(sb, keyName))
                return valueIn;
            if (sb.length() == 0) {
                if (curr.unexpectedSize() > 0)
                    break;
                return valueIn;
            }

            // if no old field nor current field matches, set to default values.
            // we may come back and set the field later if we find it.
            curr.addUnexpected(position);
            long toSkip = valueIn.readLengthMarshallable();
            bytes.readSkip(toSkip);
            consumePadding(1);
        }

        return read2(keyName, keyCode, defaultValue, curr, sb, keyName);
    }

    protected ValueIn read2(CharSequence keyName, int keyCode, Object defaultValue, @NotNull ValueInState curr, @NotNull StringBuilder sb, @NotNull CharSequence name) {
        long position2 = bytes.readPosition();

        // if not a match go back and look at old fields.
        for (int i = 0; i < curr.unexpectedSize(); i++) {
            bytes.readPosition(curr.unexpected(i));
            readField(sb);
            if (sb.length() == 0 || StringUtils.equalsCaseIgnore(sb, name)) {
                // if an old field matches, remove it, save the current position
                curr.removeUnexpected(i);
                curr.savedPosition(position2 + 1);
                return valueIn;
            }
        }
        bytes.readPosition(position2);

        if (defaultValueIn == null)
            defaultValueIn = new DefaultValueIn(this);
        defaultValueIn.defaultValue = defaultValue;
        return defaultValueIn;
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull StringBuilder name) {
        consumePadding();
        readField(name);
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn getValueIn() {
        return valueIn;
    }

    @NotNull
    @Override
    public Wire readComment(@NotNull StringBuilder s) {
        consumeWhiteSpace();
        if (peekCode() == '#') {
            bytes.readSkip(1);
            consumeWhiteSpace();
            bytes.parseUtf8(s, StopCharTesters.CONTROL_STOP);
        }
        return this;
    }

    public void consumeWhiteSpace() {
        while (Character.isWhitespace(peekCode()))
            bytes.readSkip(1);
    }

    @Override
    public void clear() {
        bytes.clear();
        valueIn.resetState();
        valueOut.resetState();
    }

    @NotNull
    @Override
    public Bytes<?> bytes() {
        return bytes;
    }

    @NotNull
    @Override
    public ValueOut write() {
        return valueOut.write();
    }

    @NotNull
    @Override
    public ValueOut write(@NotNull WireKey key) {
        return valueOut.write(key);
    }

    @NotNull
    @Override
    public ValueOut write(@NotNull CharSequence name) {
        return valueOut.write(name);
    }

    @Override
    public ValueOut writeEvent(Class expectedType, Object eventKey) {
        if (eventKey instanceof WireKey)
            return writeEventName((WireKey) eventKey);
        if (eventKey instanceof CharSequence)
            return writeEventName((CharSequence) eventKey);
        boolean wasLeft = valueOut.swapLeaf(true);
        try {
            return valueOut.write(expectedType, eventKey);
        } finally {
            valueOut.swapLeaf(wasLeft);
        }
    }

    @NotNull
    @Override
    public WireOut dropDefault(boolean dropDefault) {
        valueOut.dropDefault = dropDefault;
        return this;
    }

    @NotNull
    @Override
    public ValueOut getValueOut() {
        return valueOut;
    }

    @NotNull
    @Override
    public Wire writeComment(@NotNull CharSequence s) {
        valueOut.writeComment(s);
        return this;
    }

    @NotNull
    @Override
    public WireOut addPadding(int paddingToAdd) {
        for (int i = 0; i < paddingToAdd; i++)
            bytes.writeUnsignedByte((bytes.writePosition() & 63) == 0 ? '\n' : ' ');
        return this;
    }

    void escape(@NotNull CharSequence s) {
        @NotNull Quotes quotes = needsQuotes(s);
        if (quotes == Quotes.NONE) {
            escape0(s, quotes);
            return;
        }
        bytes.writeUnsignedByte(quotes.q);
        escape0(s, quotes);
        bytes.writeUnsignedByte(quotes.q);
    }

    protected void escape0(@NotNull CharSequence s, @NotNull Quotes quotes) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\0':
                    bytes.appendUtf8("\\0");
                    break;
                case 7:
                    bytes.appendUtf8("\\a");
                    break;
                case '\b':
                    bytes.appendUtf8("\\b");
                    break;
                case '\t':
                    bytes.appendUtf8("\\t");
                    break;
                case '\n':
                    bytes.appendUtf8("\\n");
                    break;
                case 0xB:
                    bytes.appendUtf8("\\v");
                    break;
                case 0xC:
                    bytes.appendUtf8("\\f");
                    break;
                case '\r':
                    bytes.appendUtf8("\\r");
                    break;
                case 0x1B:
                    bytes.appendUtf8("\\e");
                    break;
                case '"':
                    if (ch == quotes.q) {
                        bytes.writeUnsignedByte('\\').writeUnsignedByte(ch);
                    } else {
                        bytes.writeUnsignedByte(ch);
                    }
                    break;
                case '\'':
                    if (ch == quotes.q) {
                        bytes.writeUnsignedByte('\\').writeUnsignedByte(ch);
                    } else {
                        bytes.writeUnsignedByte(ch);
                    }
                    break;
                case '\\':
                    bytes.writeUnsignedByte('\\').writeUnsignedByte(ch);
                    break;
                case 0x85:
                    bytes.appendUtf8("\\N");
                    break;
                case 0xA0:
                    bytes.appendUtf8("\\_");
                    break;
                default:
                    if (ch > 255)
                        appendU4(ch);
                    else if (ch < ' ' || ch > 127)
                        appendX2(ch);
                    else
                        bytes.appendUtf8(ch);
                    break;
            }
        }
    }

    private void appendX2(char ch) {
        bytes.append('\\');
        bytes.append('x');
        bytes.append(HEXADECIMAL[(ch >> 4) & 0xF]);
        bytes.append(HEXADECIMAL[ch & 0xF]);
    }

    private void appendU4(char ch) {
        bytes.append('\\');
        bytes.append('u');
        bytes.append(HEXADECIMAL[ch >> 12]);
        bytes.append(HEXADECIMAL[(ch >> 8) & 0xF]);
        bytes.append(HEXADECIMAL[(ch >> 4) & 0xF]);
        bytes.append(HEXADECIMAL[ch & 0xF]);
    }

    @NotNull
    protected Quotes needsQuotes(@NotNull CharSequence s) {
        @NotNull Quotes quotes = Quotes.NONE;
        if (s.length() == 0)
            return Quotes.DOUBLE;

        if (STARTS_QUOTE_CHARS.get(s.charAt(0)) ||
                Character.isWhitespace(s.charAt(s.length() - 1)))
            return Quotes.DOUBLE;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (QUOTE_CHARS.get(ch) || ch < ' ' || ch > 127)
                return Quotes.DOUBLE;
            if (ch == '"')
                quotes = Quotes.SINGLE;
        }
        return quotes;
    }

    @NotNull
    @Override
    public LongValue newLongReference() {
        return new TextLongReference();
    }

    @NotNull
    @Override
    public IntValue newIntReference() {
        return new TextIntReference();
    }

    @NotNull
    @Override
    public LongArrayValues newLongArrayReference() {
        return new TextLongArrayReference();
    }

    public void parseWord(@NotNull StringBuilder sb) {
        parseUntil(sb, StopCharTesters.SPACE_STOP);
    }

    public void parseUntil(@NotNull StringBuilder sb, @NotNull StopCharTester testers) {
        if (use8bit)
            bytes.parse8bit(sb, testers);
        else
            bytes.parseUtf8(sb, testers);
    }

    public void parseUntil(@NotNull StringBuilder sb, @NotNull StopCharsTester testers) {
        sb.setLength(0);
        if (use8bit) {
            AppendableUtil.read8bitAndAppend(bytes, sb, testers);
        } else {
            AppendableUtil.readUTFAndAppend(bytes, sb, testers);
        }
    }

    public void append(@NotNull CharSequence cs) {
        if (use8bit)
            bytes.append8bit(cs);
        else
            bytes.appendUtf8(cs);
    }

    public void append(@NotNull CharSequence cs, int offset, int length) {
        if (use8bit)
            bytes.append8bit(cs, offset, offset + length);
        else
            bytes.appendUtf8(cs, offset, length);
    }

    @Nullable
    public Object readObject() {
        consumePadding();
        consumeDocumentStart();
        return readObject(0);
    }

    @Nullable
    Object readObject(int indentation) {
        consumePadding();
        int code = peekCode();
        int indentation2 = indentation();
        if (indentation2 < indentation)
            return NoObject.NO_OBJECT;
        switch (code) {
            case '-':
                if (bytes.readByte(bytes.readPosition() + 1) == '-')
                    return NoObject.NO_OBJECT;

                return readList(indentation2, null);
            case '[':
                return readList();
            case '{':
                return valueIn.marshallableAsMap(Object.class, Object.class);
            case '!':
                return readTypedObject();
            default:
                return readMap(indentation2, null);
        }
    }

    private int indentation() {
        long pos = bytes.readPosition();
        if (pos < lineStart) {
            lineStart = pos;
            return 0;
        }
        return Maths.toInt32(pos - lineStart);
    }

    @Nullable
    private Object readTypedObject() {
        return valueIn.object(Object.class);
    }

    @NotNull
    private List readList() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    List readList(int indentation, Class elementType) {
        @NotNull List<Object> objects = new ArrayList<>();
        while (peekCode() == '-') {
            if (indentation() < indentation)
                break;
            if (bytes.readByte(bytes.readPosition() + 1) == '-')
                break;
            long ls = lineStart;
            bytes.readSkip(1);
            consumePadding();
            if (lineStart == ls) {
                objects.add(valueIn.objectWithInferredType(null, SerializationStrategies.ANY_OBJECT, elementType));
            } else {
                @Nullable Object e = readObject(indentation);
                if (e != NoObject.NO_OBJECT)
                    objects.add(e);
            }
            consumePadding(1);
        }

        return objects;
    }

    @NotNull
    private Map readMap(int indentation, Class valueType) {
        @NotNull Map map = new LinkedHashMap<>();
        StringBuilder sb = WireInternal.acquireAnotherStringBuilder(acquireStringBuilder());
        consumePadding();
        while (bytes.readRemaining() > 0) {
            if (indentation() < indentation || bytes.readRemaining() == 0)
                break;
            read(sb);
            @Nullable String key = WireInternal.INTERNER.intern(sb);
            if (key.equals("..."))
                break;
            @Nullable Object value = valueIn.objectWithInferredType(null, SerializationStrategies.ANY_OBJECT, valueType);
            map.put(key, value);
            consumePadding(1);
        }
        return map;
    }

    public void writeObject(Object o) {
        if (o instanceof Iterable) {
            for (Object o2 : (Iterable) o) {
                writeObject(o2, 2);
            }
        } else if (o instanceof Map) {
            for (@NotNull Map.Entry<Object, Object> entry : ((Map<Object, Object>) o).entrySet()) {
                write(() -> entry.getKey().toString()).object(entry.getValue());
            }
        } else if (o instanceof WriteMarshallable) {
            valueOut.typedMarshallable((WriteMarshallable) o);

        } else {
            valueOut.object(o);
        }
    }

    private void writeObject(Object o, int indentation) {
        writeTwo('-', ' ');
        indentation(indentation - 2);
        valueOut.object(o);
    }

    private void indentation(int indentation) {
        while (indentation-- > 0)
            bytes.writeUnsignedByte(' ');
    }

    public void startEvent() {
        valueOut.prependSeparator();
        writeTwo('?', ' ');
    }

    @Override
    public void endEvent() {
        valueOut.endEvent();
    }

    void writeTwo(char ch1, char ch2) {
        bytes.writeUnsignedByte(ch1);
        bytes.writeUnsignedByte(ch2);
    }

    enum NoObject {NO_OBJECT}

    class TextValueOut implements ValueOut {
        protected int indentation = 0;
        @NotNull
        protected List<BytesStore> seps = new ArrayList<>(4);
        @NotNull
        protected BytesStore sep = BytesStore.empty();
        protected boolean leaf = false;
        protected boolean dropDefault = false;
        @Nullable
        private String eventName;

        public void resetState() {
            indentation = 0;
            seps.clear();
            sep = empty();
            leaf = false;
            dropDefault = false;
            eventName = null;
        }

        void prependSeparator() {
            append(sep);
            if (sep.endsWith('\n') || sep == EMPTY_AFTER_COMMENT)
                indent();
            sep = BytesStore.empty();
        }

        @NotNull
        @Override
        public boolean swapLeaf(boolean isLeaf) {
            if (isLeaf == leaf)
                return leaf;
            leaf = isLeaf;
            if (!isLeaf && sep.startsWith(','))
                elementSeparator();
            return !leaf;
        }

        @NotNull
        @Override
        public WireOut wireOut() {
            return TextWire.this;
        }

        private void indent() {
            for (int i = 0; i < indentation; i++) {
                bytes.writeUnsignedShort(' ' * 257);
            }
        }

        public void elementSeparator() {
            if (indentation == 0) {
                if (leaf) {
                    sep = COMMA_SPACE;
                } else {
                    sep = BytesStore.empty();
                    bytes.writeUnsignedByte('\n');
                }

            } else {
                sep = leaf ? COMMA_SPACE : COMMA_NEW_LINE;
            }
        }

        @NotNull
        @Override
        public WireOut bool(@Nullable Boolean flag) {
            if (dropDefault) {
                if (flag == null)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            append(flag == null ? nullOut() : flag ? "true" : "false");
            elementSeparator();
            return wireOut();
        }

        @NotNull
        public String nullOut() {
            return "!" + NULL;
        }

        @NotNull
        @Override
        public WireOut text(@Nullable CharSequence s) {
            if (dropDefault) {
                if (s == null)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            if (s == null) {
                append(nullOut());
            } else {
                escape(s);
            }
            elementSeparator();
            return wireOut();
        }

        @NotNull
        @Override
        public WireOut bytes(@Nullable BytesStore fromBytes) {
            if (dropDefault) {
                if (fromBytes == null)
                    return wireOut();
                writeSavedEventName();
            }
            if (isText(fromBytes))
                return text(fromBytes);

            int length = Maths.toInt32(fromBytes.readRemaining());
            @NotNull byte[] byteArray = new byte[length];
            fromBytes.copyTo(byteArray);

            return bytes(byteArray);
        }

        @NotNull
        @Override
        public WireOut rawBytes(@NotNull byte[] value) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            bytes.write(value);
            elementSeparator();
            return wireOut();
        }

        private boolean isText(@Nullable BytesStore fromBytes) {

            if (fromBytes == null)
                return true;
            for (long i = fromBytes.readPosition(); i < fromBytes.readLimit(); i++) {
                int ch = fromBytes.readUnsignedByte(i);
                if ((ch < ' ' && ch != '\t') || ch >= 127)
                    return false;
            }
            return true;
        }

        @NotNull
        @Override
        public ValueOut writeLength(long remaining) {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public WireOut bytes(byte[] byteArray) {
            if (dropDefault) {
                writeSavedEventName();
            }
            return bytes("!binary", byteArray);
        }

        @NotNull
        @Override
        public WireOut bytes(@NotNull String type, byte[] byteArray) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            typePrefix(type);
            append(Base64.getEncoder().encodeToString(byteArray));
            append(leaf ? SPACE : END_FIELD);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut bytes(@NotNull String type, @NotNull BytesStore bytesStore) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            typePrefix(type);
            append(Base64.getEncoder().encodeToString(bytesStore.toByteArray()));
            append(END_FIELD);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int8(byte i8) {
            if (dropDefault) {
                if (i8 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(i8);
            elementSeparator();
            return wireOut();
        }

        @NotNull
        @Override
        public WireOut uint8checked(int u8) {
            if (dropDefault) {
                if (u8 == 0)
                    return wireOut();
                writeSavedEventName();
            }

            prependSeparator();
            bytes.append(u8);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int16(short i16) {
            if (dropDefault) {
                if (i16 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(i16);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uint16checked(int u16) {
            if (dropDefault) {
                if (u16 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(u16);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut utf8(int codepoint) {
            if (dropDefault) {
                if (codepoint == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            @NotNull StringBuilder sb = acquireStringBuilder();
            sb.appendCodePoint(codepoint);
            text(sb);
            sep = empty();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int32(int i32) {
            if (dropDefault) {
                if (i32 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(i32);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uint32checked(long u32) {
            if (dropDefault) {
                if (u32 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(u32);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64(long i64) {
            if (dropDefault) {
                if (i64 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.append(i64);
            elementSeparator();
            // 2001 to 2100 best effort basis.
            if (TextWire.this.addTimeStamps && !leaf && 1_000_000_000_000L < i64 && i64 < 4_111_000_000_000L) {
                bytes.append(", # ");
                bytes.appendDateMillis(i64);
                bytes.append("T");
                bytes.appendTimeMillis(i64);
                sep = NEW_LINE;
            }

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64_0x(long i64) {
            if (dropDefault) {
                if (i64 == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            bytes.writeUnsignedByte('0')
                    .writeUnsignedByte('x')
                    .appendBase(i64, 16);
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity) {
            if (dropDefault) {
                writeSavedEventName();
            }
            TextLongArrayReference.write(bytes, capacity);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity, @NotNull LongArrayValues values) {
            if (dropDefault) {
                writeSavedEventName();
            }
            long pos = bytes.writePosition();
            TextLongArrayReference.write(bytes, capacity);
            ((Byteable) values).bytesStore(bytes, pos, bytes.writePosition() - pos);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut float32(float f) {
            if (dropDefault) {
                if (f == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            double af = Math.abs(f);
            if (af >= 1e-3 && af < 1e6)
                bytes.append(f);
            else
                bytes.append(Float.toString(f));
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut float64(double d) {
            if (dropDefault) {
                if (d == 0)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            double ad = Math.abs(d);
            if (ad >= 1e-3 && ad < 1e15) {
                if ((int) (ad / 1e6) * 1e6 == ad)
                    bytes.append((int) (d / 1e6)).append("e6");
                else if ((int) (ad / 1e3) * 1e3 == ad)
                    bytes.append((int) (d / 1e3)).append("e3");
                else
                    bytes.append(d);
            } else {
                bytes.append(Double.toString(d));
            }
            elementSeparator();

            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut time(LocalTime localTime) {
            return asText(localTime);
        }

        @NotNull
        @Override
        public WireOut zonedDateTime(@Nullable ZonedDateTime zonedDateTime) {
            if (dropDefault) {
                if (zonedDateTime == null)
                    return wireOut();
                writeSavedEventName();
            }
            final String s = zonedDateTime.toString();
            return s.endsWith("]") ? text(s) : asText(s);
        }

        @NotNull
        @Override
        public WireOut date(LocalDate localDate) {
            return asText(localDate);
        }

        @NotNull
        @Override
        public WireOut dateTime(LocalDateTime localDateTime) {
            return asText(localDateTime);
        }

        @NotNull
        private WireOut asText(@Nullable Object stringable) {
            if (dropDefault) {
                if (stringable == null)
                    return wireOut();
                writeSavedEventName();
            }
            if (stringable == null) {
                nu11();
            } else {
                prependSeparator();
                append(stringable.toString());
                elementSeparator();
            }

            return TextWire.this;
        }

        @NotNull
        @Override
        public ValueOut optionalTyped(Class aClass) {
            return typePrefix(aClass);
        }

        @NotNull
        @Override
        public ValueOut typePrefix(@NotNull CharSequence typeName) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            bytes.writeUnsignedByte('!');
            append(typeName);
            bytes.writeUnsignedByte(' ');
            sep = BytesStore.empty();
            return this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull BiConsumer<Class, Bytes> typeTranslator, Class type) {
            if (dropDefault) {
                if (type == null)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            append(TYPE);
            typeTranslator.accept(type, bytes);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull CharSequence type) {
            if (dropDefault) {
                if (type == null)
                    return wireOut();
                writeSavedEventName();
            }
            prependSeparator();
            append(TYPE);
            escape(type);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut uuid(@NotNull UUID uuid) {
            return asText(uuid);
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            TextIntReference.write(bytes, value);
            elementSeparator();
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value, IntValue intValue) {
            if (dropDefault) {
                writeSavedEventName();
            }
            if (!TextIntReference.class.isInstance(intValue))
                throw new IllegalArgumentException();
            prependSeparator();
            long offset = bytes.writePosition();
            TextIntReference.write(bytes, value);
            long length = bytes.writePosition() - offset;
            ((Byteable) intValue).bytesStore(bytes, offset, length);
            elementSeparator();
            return wireOut();
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value) {
            if (dropDefault) {
                writeSavedEventName();
            }
            prependSeparator();
            TextLongReference.write(bytes, value);
            elementSeparator();
            return wireOut();
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value, LongValue longValue) {
            if (dropDefault) {
                writeSavedEventName();
            }
            if (!TextLongReference.class.isInstance(longValue))
                throw new IllegalArgumentException();
            prependSeparator();
            long offset = bytes.writePosition();
            TextLongReference.write(bytes, value);
            long length = bytes.writePosition() - offset;
            ((Byteable) longValue).bytesStore(bytes, offset, length);
            elementSeparator();
            return wireOut();
        }

        @NotNull
        @Override
        public <T> WireOut sequence(T t, @NotNull BiConsumer<T, ValueOut> writer) {
            startBlock('[');
            boolean leaf = this.leaf;
            if (!leaf)
                newLine();
            long pos = bytes.readPosition();
            writer.accept(t, this);
            if (!leaf)
                addNewLine(pos);

            popState();
            if (!leaf)
                indent();
            endBlock(leaf, ']');
            return wireOut();
        }

        public void startBlock(char c) {
            if (dropDefault) {
                writeSavedEventName();
            }
            if (!sep.isEmpty()) {
                append(sep);
                indent();
                sep = EMPTY;
            }
            pushState();
            bytes.writeUnsignedByte(c);
        }

        @NotNull
        @Override
        public <T, K> WireOut sequence(T t, K kls, @NotNull TriConsumer<T, K, ValueOut> writer) {
            boolean leaf = this.leaf;
            startBlock('[');
            if (leaf)
                sep = SPACE;
            else
                newLine();
            long pos = bytes.readPosition();
            writer.accept(t, kls, this);
            if (leaf)
                addSpace(pos);
            else
                addNewLine(pos);

            popState();
            if (!leaf)
                indent();
            endBlock(leaf, ']');
            return wireOut();
        }

        public void endBlock(boolean leaf, char c) {
            bytes.writeUnsignedByte(c);
            sep = this.leaf ? COMMA_SPACE : COMMA_NEW_LINE;
        }

        protected void addNewLine(long pos) {
            if (bytes.writePosition() > pos + 1)
                bytes.writeUnsignedByte('\n');
        }

        protected void addSpace(long pos) {
            if (bytes.writePosition() > pos + 1)
                bytes.writeUnsignedByte(' ');
        }

        protected void newLine() {
            sep = NEW_LINE;
        }

        protected void popState() {
            sep = seps.remove(seps.size() - 1);
            indentation--;
            leaf = false;
            dropDefault = false;
        }

        protected void pushState() {
            indentation++;
            seps.add(sep);
            sep = EMPTY;
        }

        @NotNull
        @Override
        public WireOut marshallable(@NotNull WriteMarshallable object) {
            WireMarshaller wm = WireMarshaller.WIRE_MARSHALLER_CL.get(object.getClass());
            boolean wasLeaf0 = leaf;
            if (indentation > 1 && wm.isLeaf())
                leaf = true;

            if (dropDefault) {
                writeSavedEventName();
            }
            if (bytes.writePosition() == 0) {
                object.writeMarshallable(TextWire.this);
                if (bytes.writePosition() == 0)
                    bytes.append("{}");
                return TextWire.this;
            }
            boolean wasLeaf = leaf;
            startBlock('{');

            if (wasLeaf)
                afterOpen();
            else
                newLine();

            object.writeMarshallable(TextWire.this);
            @Nullable BytesStore popSep = null;
            if (wasLeaf) {
                if (sep.endsWith(' '))
                    append(" ");
                leaf = false;
                popState();
            } else if (seps.size() > 0) {
                popSep = seps.get(seps.size() - 1);
                popState();
                sep = NEW_LINE;
            }
            if (sep.startsWith(',')) {
                append(sep, 1, sep.length() - 1);
                if (!wasLeaf)
                    indent();

            } else {
                prependSeparator();
            }
            endBlock(leaf, '}');

            leaf = wasLeaf0;

            if (popSep != null)
                sep = popSep;
            if (indentation == 0) {
                afterClose();

            } else {
                elementSeparator();
            }
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireOut marshallable(@NotNull Serializable object) {
            if (dropDefault) {
                writeSavedEventName();
            }
            if (bytes.writePosition() == 0) {
                writeSerializable(object);
                return TextWire.this;
            }
            boolean wasLeaf = leaf;
            if (!wasLeaf)
                pushState();

            prependSeparator();
            bytes.writeUnsignedByte(object instanceof Externalizable ? '[' : '{');
            if (wasLeaf)
                afterOpen();
            else
                newLine();

            writeSerializable(object);
            @Nullable BytesStore popSep = null;
            if (wasLeaf) {
                leaf = false;
            } else if (seps.size() > 0) {
                popSep = seps.get(seps.size() - 1);
                popState();
                sep = NEW_LINE;
            }
            if (sep.startsWith(',')) {
                append(sep, 1, sep.length() - 1);
                if (!wasLeaf)
                    indent();

            } else {
                prependSeparator();
            }
            bytes.writeUnsignedByte(object instanceof Externalizable ? ']' : '}');
            if (popSep != null)
                sep = popSep;
            if (indentation == 0) {
                afterClose();

            } else {
                elementSeparator();
            }
            return TextWire.this;
        }

        private void writeSerializable(@NotNull Serializable object) {
            try {
                if (object instanceof Externalizable)
                    ((Externalizable) object).writeExternal(objectOutput());
                else
                    Wires.writeMarshallable(object, TextWire.this);
            } catch (IOException e) {
                throw new IORuntimeException(e);
            }
        }

        protected void afterClose() {
            newLine();
            append(sep);
            sep = EMPTY;
        }

        protected void afterOpen() {
            sep = SPACE;
        }

        @NotNull
        @Override
        public WireOut map(@NotNull final Map map) {
            if (dropDefault) {
                writeSavedEventName();
            }
            marshallable(map, Object.class, Object.class, false);
            return TextWire.this;
        }

        protected void endField() {
            sep = END_FIELD;
        }

        private void object2(Object v) {
            if (v instanceof CharSequence)
                text((CharSequence) v);
            else if (v instanceof WriteMarshallable)
                typedMarshallable((WriteMarshallable) v);
            else if (v == null)
                append(nullOut());
            else
                text(String.valueOf(v));
        }

        @NotNull
        @Override
        public WireOut typedMap(@NotNull Map<? extends WriteMarshallable, ? extends Marshallable> map) {
            if (dropDefault) {
                writeSavedEventName();
            }
            typePrefix(SEQ_MAP);
            map.forEach((k, v) -> sequence(w -> w.marshallable(m -> m
                    .write(() -> "key").typedMarshallable(k)
                    .write(() -> "value").typedMarshallable(v))));
            return wireOut();
        }

        protected void fieldValueSeperator() {
            writeTwo(':', ' ');
        }

        @NotNull
        public ValueOut write() {
            if (dropDefault) {
                eventName = "";
            } else {
                append(sep);
                writeTwo('"', '"');
                endEvent();
            }
            return this;
        }

        @NotNull
        public ValueOut write(@NotNull WireKey key) {
            if (dropDefault) {
                eventName = key.name().toString();
            } else {
                write(key.name());
            }
            return this;
        }

        @NotNull
        public ValueOut write(@NotNull CharSequence name) {
            if (dropDefault) {
                eventName = name.toString();
            } else {
                prependSeparator();
                escape(name);
                fieldValueSeperator();
            }
            return this;
        }

        @NotNull
        public ValueOut write(Class expectedType, @NotNull Object objectKey) {
            if (dropDefault) {
                if (expectedType != String.class)
                    throw new UnsupportedOperationException();
                eventName = objectKey.toString();
            } else {
                prependSeparator();
                startEvent();
                object(expectedType, objectKey);
                endEvent();
            }
            return this;
        }

        private void writeSavedEventName() {
            if (eventName == null)
                return;
            prependSeparator();
            escape(eventName);
            fieldValueSeperator();
            eventName = null;
        }

        public void endEvent() {
            if (bytes.readByte(bytes.writePosition() - 1) <= ' ')
                bytes.writeSkip(-1);
            fieldValueSeperator();
            sep = empty();
        }

        public void writeComment(@NotNull CharSequence s) {
            prependSeparator();
            append(sep);
            writeTwo('#', ' ');
            append(s);
            bytes.writeUnsignedByte('\n');
            sep = EMPTY_AFTER_COMMENT;
        }
    }

    class TextValueIn implements ValueIn {
        final ValueInStack stack = new ValueInStack();
        int sequenceLimit = 0;

        @Override
        public void resetState() {
            stack.reset();
        }

        public void pushState() {
            stack.push();
        }

        public void popState() {
            stack.pop();
        }

        public ValueInState curr() {
            return stack.curr();
        }

        @Nullable
        @Override
        public String text() {
            @Nullable CharSequence cs = textTo0(acquireStringBuilder());
            return cs == null ? null : WireInternal.INTERNER.intern(cs);
        }

        @Nullable
        @Override
        public StringBuilder textTo(@NotNull StringBuilder sb) {
            sb.setLength(0);
            @Nullable CharSequence cs = textTo0(sb);
            if (cs == null)
                return null;
            if (cs != sb) {
                sb.setLength(0);
                sb.append(cs);
            }
            return sb;
        }

        @Nullable
        @Override
        public Bytes textTo(@NotNull Bytes bytes) {
            bytes.clear();
            @Nullable CharSequence cs = textTo0(bytes);
            if (cs == null)
                return null;
            if (cs != bytes) {
                bytes.clear();
                bytes.writeUtf8(cs);
            }
            return bytes;
        }

        @NotNull
        @Override
        public BracketType getBracketType() {
            consumePadding();
            switch (peekCode()) {
                case '{':
                    return BracketType.MAP;
                case '[':
                    return BracketType.SEQ;
                default:
                    return BracketType.NONE;
            }
        }

        @Nullable
        <ACS extends Appendable & CharSequence> CharSequence textTo0(@NotNull ACS a) {
            consumePadding();
            int ch = peekCode();
            @Nullable CharSequence ret = a;

            switch (ch) {
                case '{': {
                    final long len = readLength();
                    try {
                        a.append(Bytes.toString(bytes, bytes.readPosition(), len));
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    bytes.readSkip(len);

                    // read the next comma
                    bytes.skipTo(StopCharTesters.COMMA_STOP);

                    return a;

                }
                case '"':
                    readText(a, getEscapingQuotes());
                    break;

                case '\'':
                    readText(a, getEscapingSingleQuotes());
                    break;

                case '!': {
                    bytes.readSkip(1);
                    @NotNull StringBuilder sb = acquireStringBuilder();
                    parseWord(sb);
                    if (StringUtils.isEqual(sb, "!null")) {
                        textTo(sb);
                        ret = null;
                    } else if (StringUtils.isEqual(sb, "snappy")) {
                        textTo(sb);
                        try {
                            //todo needs to be made efficient
                            byte[] decodedBytes = Base64.getDecoder().decode(sb.toString().getBytes(ISO_8859_1));
                            String csq = Snappy.uncompressString(decodedBytes);
                            ret = acquireStringBuilder().append(csq);
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    } else {
                        // ignore the type.
                        if (a instanceof StringBuilder) {
                            textTo((StringBuilder) a);
                        } else {
                            textTo(sb);
                            ret = sb;
                        }
                    }
                    break;
                }

                case -1:
                    return "";

                default: {
                    final long rem = bytes.readRemaining();
                    if (rem > 0) {
                        if (a instanceof Bytes || use8bit) {
                            bytes.parse8bit(a, getStrictEscapingEndOfText());
                        } else {
                            bytes.parseUtf8(a, getStrictEscapingEndOfText());
                        }
                        if (rem == bytes.readRemaining())
                            throw new IORuntimeException("Nothing to read at " + bytes.toDebugString(32));
                    } else {
                        AppendableUtil.setLength(a, 0);
                    }
                    // trim trailing spaces.
                    while (a.length() > 0)
                        if (Character.isWhitespace(a.charAt(a.length() - 1)))
                            AppendableUtil.setLength(a, a.length() - 1);
                        else
                            break;
                    break;
                }
            }

            int prev = peekBack();
            if (prev == ':' || prev == '#' || prev == '}' || prev == ']')
                bytes.readSkip(-1);
            return ret;
        }

        private <ACS extends Appendable & CharSequence> void readText(@NotNull ACS a, @NotNull StopCharTester quotes) {
            bytes.readSkip(1);
            if (use8bit)
                bytes.parse8bit(a, quotes);
            else
                bytes.parseUtf8(a, quotes);
            unescape(a);
            consumePadding(1);
        }

        protected int peekBack() {
            while (bytes.readPosition() >= bytes.start()) {
                int prev = bytes.readUnsignedByte(bytes.readPosition() - 1);
                if (prev != ' ') {
                    if (prev == '\n' || prev == '\r') {
                        TextWire.this.lineStart = bytes.readPosition();
                    }
                    return prev;
                }
                bytes.readSkip(-1);
            }
            return -1;
        }

        @NotNull
        @Override
        public WireIn bytesMatch(@NotNull BytesStore compareBytes, BooleanConsumer consumer) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public WireIn bytes(@NotNull BytesOut toBytes) {
            toBytes.clear();
            return bytes(b -> toBytes.write((BytesStore) b));
        }

        @Nullable
        @Override
        public WireIn bytesSet(@NotNull PointerBytesStore toBytes) {
            return bytes(bytes -> {
                long capacity = bytes.readRemaining();
                Bytes<Void> bytes2 = Bytes.allocateDirect(capacity);
                bytes2.write((BytesStore) bytes);
                toBytes.set(bytes2.address(bytes2.start()), capacity);
            });
        }

        @NotNull
        public WireIn bytes(@NotNull ReadBytesMarshallable bytesConsumer) {
            consumePadding();
            try {
                // TODO needs to be made much more efficient.
                @NotNull StringBuilder sb = acquireStringBuilder();
                if (peekCode() == '!') {
                    bytes.readSkip(1);
                    parseWord(sb);
                    @Nullable byte[] uncompressed = Compression.uncompress(sb, TextWire.this, t -> {
                        @NotNull StringBuilder sb2 = acquireStringBuilder();
                        AppendableUtil.setLength(sb2, 0);
                        t.parseWord(sb2);
                        byte[] decode = Base64.getDecoder().decode(sb2.toString());
                        return decode;
                    });
                    if (uncompressed != null) {
                        bytesConsumer.readMarshallable(Bytes.wrapForRead(uncompressed));

                    } else if (StringUtils.isEqual(sb, "!null")) {
                        bytesConsumer.readMarshallable(null);
                        parseWord(sb);
                    } else {
                        throw new IORuntimeException("Unsupported type=" + sb);
                    }
                } else {
                    textTo(sb);
                    bytesConsumer.readMarshallable(Bytes.wrapForRead(sb.toString().getBytes(ISO_8859_1)));
                }
                return TextWire.this;
            } finally {
                consumePadding(1);
            }
        }

        @Nullable
        public byte[] bytes() {
            consumePadding();
            try {
                // TODO needs to be made much more efficient.
                @NotNull StringBuilder sb = acquireStringBuilder();
                if (peekCode() == '!') {
                    bytes.readSkip(1);
                    parseWord(sb);

                    if ("byte[]".contentEquals(sb)) {
                        bytes.readSkip(1);
                        parseWord(sb);
                    }

                    @Nullable byte[] bytes = Compression.uncompress(sb, this, t -> {
                        @NotNull StringBuilder sb0 = acquireStringBuilder();
                        AppendableUtil.setLength(sb0, 0);
                        parseWord(sb0);
                        return Base64.getDecoder().decode(WireInternal.INTERNER.intern(sb));
                    });
                    if (bytes != null)
                        return bytes;

                    if ("!null".contentEquals(sb)) {
                        parseWord(sb);
                        return null;
                    }

                    throw new IllegalStateException("unsupported type=" + sb);

                } else {
                    textTo(sb);
                    // todo fix this.
                    return sb.toString().getBytes(ISO_8859_1);
                }
            } finally {
                consumePadding(1);
            }
        }

        @NotNull
        @Override
        public WireIn wireIn() {
            return TextWire.this;
        }

        @Override
        public long readLength() {
            return readLengthMarshallable();
        }

        @NotNull
        @Override
        public WireIn skipValue() {
            consumeAny();
            if (peekCode() == ',')
                bytes.readSkip(1);
            return TextWire.this;
        }

        protected long readLengthMarshallable() {
            long start = bytes.readPosition();
            try {
                consumeAny();
                return bytes.readPosition() - start;
            } finally {
                bytes.readPosition(start);
            }
        }

        protected void consumeAny() {
            consumePadding();
            int code = peekCode();
            switch (code) {
                case '{': {
                    bytes.readSkip(1);
                    for (; ; ) {
                        long pos = bytes.readPosition();
                        consumeAny();
                        int code2 = peekCode();
                        if (code2 == '}' || code2 == ']' || code2 <= 0) {
                            break;
                        } else if (code2 == ',') {
                            readCode();
                        }
                        if (bytes.readPosition() == pos)
                            throw new IllegalStateException("Stuck at pos " + pos + " " + bytes);
                    }
                    consumePadding();
                    code = readCode();
                    if (code != '}') {
                        bytes.readSkip(-1);
                        throw new IllegalStateException("Expected a } was " + bytes);
                    }
                    break;
                }
                case '[': {
                    bytes.readSkip(1);
                    for (; ; ) {
                        long pos = bytes.readPosition();
                        consumeAny();
                        if (peekCode() == ',')
                            readCode();
                        else
                            break;
                        if (bytes.readPosition() == pos)
                            throw new IllegalStateException("Stuck at pos " + pos + " " + bytes);
                    }
                    consumePadding();
                    code = readCode();
                    if (code != ']') {
                        bytes.readSkip(-1);
                        throw new IllegalStateException("Expected a ] was " + bytes);
                    }
                    break;
                }
                case '}':
                    break;
                case ']':
                    break;
                case '?':
                    bytes.readSkip(1);
                    consumeAny();
                    if (peekCode() == ':') {
                        bytes.readSkip(1);
                        consumeAny();
                    }
                    break;
                case '"':
                case '\'':
                default:
                    consumeValue();
                    if (peekBack() == ',') {
                        bytes.readSkip(-1);
                        break;
                    }
                    consumePadding();
                    if (peekCode() == ':') {
                        readCode();
                        consumeAny();
                    }
                    break;
            }
        }

        private void consumeValue() {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            if (peekCode() == '!') {
                bytes.readSkip(1);
                parseWord(sb);
                if (StringUtils.isEqual(sb, "type")) {
                    consumeType();
                } else {
                    consumeAny();
                }

            } else {
                textTo(sb);
            }
        }

        private void consumeType() {
            parseUntil(sb, StopCharTesters.COMMA_SPACE_STOP);
        }

        @NotNull
        @Override
        public <T> WireIn bool(T t, @NotNull ObjBooleanConsumer<T> tFlag) {
            consumePadding();

            @NotNull StringBuilder sb = acquireStringBuilder();
            if (textTo(sb) == null) {
                tFlag.accept(t, null);
                return TextWire.this;
            }

            tFlag.accept(t, StringUtils.isEqual(sb, "true"));
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int8(@NotNull T t, @NotNull ObjByteConsumer<T> tb) {
            consumePadding();
            tb.accept(t, (byte) getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uint8(@NotNull T t, @NotNull ObjShortConsumer<T> ti) {
            consumePadding();
            ti.accept(t, (short) getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int16(@NotNull T t, @NotNull ObjShortConsumer<T> ti) {
            consumePadding();
            ti.accept(t, (short) getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uint16(@NotNull T t, @NotNull ObjIntConsumer<T> ti) {
            consumePadding();
            ti.accept(t, (int) getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int32(@NotNull T t, @NotNull ObjIntConsumer<T> ti) {
            consumePadding();
            ti.accept(t, (int) getALong());
            return TextWire.this;
        }

        long getALong() {
            final int code = peekCode();
            switch (code) {
                case '"':
                case '\'':
                    bytes.readSkip(1);
                    break;

                case 't':
                case 'T':
                case 'f':
                case 'F':
                    return bool() ? 1 : 0;
                case '{':
                case '[':
                    throw new IORuntimeException("Cannot read a " + (char) code + " as a number");
            }
            return bytes.parseLong();
        }

        @NotNull
        @Override
        public <T> WireIn uint32(@NotNull T t, @NotNull ObjLongConsumer<T> tl) {
            consumePadding();
            tl.accept(t, getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64(@NotNull T t, @NotNull ObjLongConsumer<T> tl) {
            consumePadding();
            tl.accept(t, getALong());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn float32(@NotNull T t, @NotNull ObjFloatConsumer<T> tf) {
            consumePadding();
            tf.accept(t, (float) bytes.parseDouble());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn float64(@NotNull T t, @NotNull ObjDoubleConsumer<T> td) {
            consumePadding();
            td.accept(t, bytes.parseDouble());
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn time(@NotNull T t, @NotNull BiConsumer<T, LocalTime> setLocalTime) {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            textTo(sb);
            setLocalTime.accept(t, LocalTime.parse(WireInternal.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn zonedDateTime(@NotNull T t, @NotNull BiConsumer<T, ZonedDateTime> tZonedDateTime) {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            textTo(sb);
            tZonedDateTime.accept(t, ZonedDateTime.parse(WireInternal.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn date(@NotNull T t, @NotNull BiConsumer<T, LocalDate> tLocalDate) {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            textTo(sb);
            tLocalDate.accept(t, LocalDate.parse(WireInternal.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uuid(@NotNull T t, @NotNull BiConsumer<T, UUID> tuuid) {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            textTo(sb);
            tuuid.accept(t, UUID.fromString(WireInternal.INTERNER.intern(sb)));
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64array(@Nullable LongArrayValues values, T t, @NotNull BiConsumer<T, LongArrayValues> setter) {
            consumePadding();
            if (!(values instanceof TextLongArrayReference)) {
                setter.accept(t, values = new TextLongArrayReference());
            }
            @NotNull Byteable b = (Byteable) values;
            long length = TextLongArrayReference.peakLength(bytes, bytes.readPosition());
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int64(@Nullable LongValue value) {
            consumePadding();
            @NotNull Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            consumePadding(1);
            return TextWire.this;
        }

        @NotNull
        @Override
        public WireIn int32(@NotNull IntValue value) {
            consumePadding();
            @NotNull Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            consumePadding(1);
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64(@Nullable LongValue value, T t, @NotNull BiConsumer<T, LongValue> setter) {
            if (!(value instanceof TextLongReference)) {
                setter.accept(t, value = new TextLongReference());
            }
            return int64(value);
        }

        @NotNull
        @Override
        public <T> WireIn int32(@Nullable IntValue value, T t, @NotNull BiConsumer<T, IntValue> setter) {
            consumePadding();
            if (!(value instanceof TextIntReference)) {
                setter.accept(t, value = new TextIntReference());
            }
            @Nullable Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            consumePadding(1);
            return TextWire.this;
        }

        @NotNull
        @Override
        public <T> boolean sequence(@NotNull T t, @NotNull BiConsumer<T, ValueIn> tReader) {
            consumePadding();

            char code = (char) peekCode();
            if (code == '!') {
                @Nullable final Class typePrefix = typePrefix();
                if (typePrefix == void.class) {
                    text();
                    return false;
                }
                consumePadding();
                code = (char) readCode();
            }
            if (code == '[') {
                bytes.readSkip(1);
                sequenceLimit = Integer.MAX_VALUE;
            } else {
                sequenceLimit = 1;
            }

            tReader.accept(t, TextWire.this.valueIn);

            if (code == '[') {
                consumePadding(1);
                char code2 = (char) readCode();
                if (code2 != ']')
                    throw new IORuntimeException("Expected a ] but got " + code2 + " (" + code2 + ")");
            }
            consumePadding(1);
            return true;
        }

        public <T> boolean sequence(@NotNull List<T> list, @NotNull List<T> buffer, @NotNull Supplier<T> bufferAdd) {
            list.clear();
            consumePadding();

            char code = (char) peekCode();
            if (code == '!') {
                @Nullable final Class typePrefix = typePrefix();
                if (typePrefix == void.class) {
                    text();
                    return false;
                }
                consumePadding();
                code = (char) readCode();
            }
            if (code == '[') {
                bytes.readSkip(1);
                sequenceLimit = Integer.MAX_VALUE;
            } else {
                sequenceLimit = 1;
            }

            while (hasNextSequenceItem()) {
                int size = list.size();
                if (buffer.size() <= size) buffer.add(bufferAdd.get());

                final T t = buffer.get(size);
                // todo fix
//                if (t instanceof Resettable) ((Resettable) t).reset();
                if (t instanceof Marshallable) Wires.reset(t);
                list.add(object(t, t.getClass()));
            }

            if (code == '[') {
                consumePadding(1);
                char code2 = (char) readCode();
                if (code2 != ']')
                    throw new IORuntimeException("Expected a ] but got " + code2 + " (" + code2 + ")");
            }
            consumePadding(1);
            return true;
        }

        @NotNull
        @Override
        public <T, K> WireIn sequence(@NotNull T t, K kls, @NotNull TriConsumer<T, K, ValueIn> tReader) {
            consumePadding();
            char code = (char) peekCode();

            if (code == '[') {
                bytes.readSkip(1);
                sequenceLimit = Integer.MAX_VALUE;
            } else {
                sequenceLimit = 1;
            }

            // this code was added to support empty sets
            consumePadding();
            char code2 = (char) peekCode();
            if (code2 == ']') {
                readCode();
                return TextWire.this;
            }

            tReader.accept(t, kls, TextWire.this.valueIn);

            if (code == '[') {
                consumePadding();
                char code3 = (char) readCode();
                if (code3 != ']')
                    throw new IORuntimeException("Expected a ] but got " + code3 + " (" + code3 + ")");
            }
            consumePadding(1);
            return TextWire.this;
        }

        @Override
        public boolean hasNext() {
            consumePadding();
            return bytes.readRemaining() > 0;
        }

        @Override
        public boolean hasNextSequenceItem() {
            if (sequenceLimit-- <= 0)
                return false;
            consumePadding();
            int ch = peekCode();
            if (ch == ',') {
                bytes.readSkip(1);
                return true;
            }
            return ch > 0 && ch != ']';
        }

        @Override
        public <T> T applyToMarshallable(@NotNull Function<WireIn, T> marshallableReader) {
            pushState();
            consumePadding();
            int code = peekCode();
            if (code != '{')
                throw new IORuntimeException("Unsupported type " + (char) code);

            final long len = readLengthMarshallable();

            final long limit = bytes.readLimit();
            final long position = bytes.readPosition();

            try {
                // ensure that you can read past the end of this marshable object
                final long newLimit = position - 1 + len;
                bytes.readLimit(newLimit);
                bytes.readSkip(1); // skip the {
                consumePadding();
                return marshallableReader.apply(TextWire.this);
            } finally {
                bytes.readLimit(limit);

                consumePadding(1);
                code = readCode();
                popState();
                if (code != '}')
                    throw new IORuntimeException("Unterminated { while reading marshallable "
                            + "bytes=" + Bytes.toString(bytes)
                    );
            }
        }

        @NotNull
        @Override
        public <T> ValueIn typePrefix(T t, @NotNull BiConsumer<T, CharSequence> ts) {
            consumePadding();
            int code = peekCode();
            @NotNull StringBuilder sb = acquireStringBuilder();
            sb.setLength(0);
            if (code == -1) {
                sb.append("java.lang.Object");
            } else if (code == '!') {
                readCode();

                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
                bytes.readSkip(-1);
                consumePadding();
            }
            return this;
        }

        @Override
        public Class typePrefix() {
            consumePadding();
            int code = peekCode();
            if (code == '!') {
                readCode();

                @NotNull StringBuilder sb = acquireStringBuilder();
                sb.setLength(0);
                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
                bytes.readSkip(-1);
                try {
                    return classLookup().forName(sb);
                } catch (ClassNotFoundException e) {
                    Jvm.warn().on(getClass(), "Unable to find " + sb + " " + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        public Object typePrefixOrObject(Class tClass) {
            consumePadding();
            int code = peekCode();
            if (code == '!') {
                readCode();

                @NotNull StringBuilder sb = acquireStringBuilder();
                sb.setLength(0);
                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
                bytes.readSkip(-1);
                try {
                    return classLookup().forName(sb);
                } catch (ClassNotFoundException e) {
                    return Wires.tupleFor(tClass, sb.toString());
                }
            }
            return Wires.dtoInterface(tClass) ? Wires.tupleFor(tClass, null) : null;
        }

        @Override
        public boolean isTyped() {
            consumePadding();
            int code = peekCode();
            return code == '!';
        }

        @NotNull
        String stringForCode(int code) {
            return code < 0 ? "Unexpected end of input" : "'" + (char) code + "'";
        }

        @NotNull
        @Override
        public <T> WireIn typeLiteralAsText(T t, @NotNull BiConsumer<T, CharSequence> classNameConsumer)
                throws IORuntimeException, BufferUnderflowException {
            consumePadding();
            int code = readCode();
            if (!peekStringIgnoreCase("type "))
                throw new UnsupportedOperationException(stringForCode(code));
            bytes.readSkip("type ".length());
            @NotNull StringBuilder sb = acquireStringBuilder();
            parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            classNameConsumer.accept(t, sb);
            return TextWire.this;
        }

        @Override
        public <T> Class<T> typeLiteral() throws IORuntimeException, BufferUnderflowException {
            consumePadding();
            int code = readCode();
            if (!peekStringIgnoreCase("type "))
                throw new UnsupportedOperationException(stringForCode(code));
            bytes.readSkip("type ".length());
            @NotNull StringBuilder sb = acquireStringBuilder();
            parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            try {
                return classLookup().forName(sb);
            } catch (ClassNotFoundException e) {
                throw new IORuntimeException(e);
            }
        }

        @Nullable
        @Override
        public Object marshallable(@NotNull Object object, @NotNull SerializationStrategy strategy)
                throws BufferUnderflowException, IORuntimeException {
            if (isNull()) {
                consumePadding(1);
                return null;
            }
            if (indentation() == 0 && peekCode() != '{') {
                strategy.readUsing(object, this);
                return object;
            }
            pushState();
            consumePadding();
            int code = peekCode();
            if (code == '!') {
                typePrefix(null, (o, x) -> { /* sets acquireStringBuilder(); */});

            } else if (code == ',') {
                Jvm.warn().on(getClass(), "Expected a {} but was blank for type " + object.getClass());
                return object;

            } else if (code != '{') {
                throw new IORuntimeException("Unsupported type " + stringForCode(code));
            }

            final long len = readLengthMarshallable();

            final long limit = bytes.readLimit();
            final long position = bytes.readPosition();

            final long newLimit = position - 1 + len;
            try {
                // ensure that you can read past the end of this marshable object

                bytes.readLimit(newLimit);
                bytes.readSkip(1); // skip the {
                consumePadding();
                object = strategy.readUsing(object, this);

            } finally {
                bytes.readLimit(limit);
                bytes.readPosition(newLimit);
                popState();
            }

            consumePadding(1);
            code = readCode();
            if (code != '}')
                throw new IORuntimeException("Unterminated { while reading marshallable " +
                        object + ",code='" + (char) code + "', bytes=" + Bytes.toString(bytes, 1024)
                );
            consumePadding(1);
            return object;
        }

        @NotNull
        public Demarshallable demarshallable(@NotNull Class clazz) {
            pushState();
            consumePadding();
            int code = peekCode();
            if (code == '!') {
                typePrefix(null, (o, x) -> { /* sets acquireStringBuilder(); */});
            } else if (code != '{') {
                throw new IORuntimeException("Unsupported type " + stringForCode(code));
            }

            final long len = readLengthMarshallable();

            final long limit = bytes.readLimit();
            final long position = bytes.readPosition();

            final long newLimit = position - 1 + len;
            Demarshallable object;
            try {
                // ensure that you can read past the end of this marshable object

                bytes.readLimit(newLimit);
                bytes.readSkip(1); // skip the {
                consumePadding();

                object = Demarshallable.newInstance(clazz, TextWire.this);
            } finally {
                bytes.readLimit(limit);
                bytes.readPosition(newLimit);
                popState();
            }

            consumePadding(1);
            code = readCode();
            if (code != '}')
                throw new IORuntimeException("Unterminated { while reading marshallable " +
                        object + ",code='" + (char) code + "', bytes=" + Bytes.toString(bytes, 1024)
                );
            return object;
        }

        @Nullable
        public <T> T typedMarshallable() {
            return (T) objectWithInferredType(null, SerializationStrategies.ANY_NESTED, null);
        }

        @Nullable
        @Override
        public <K, V> Map<K, V> map(@NotNull final Class<K> kClass,
                                    @NotNull final Class<V> vClass,
                                    @Nullable Map<K, V> usingMap) {
            consumePadding();
            if (usingMap == null)
                usingMap = new LinkedHashMap<>();
            else
                usingMap.clear();

            @NotNull StringBuilder sb = acquireStringBuilder();
            int code = peekCode();
            switch (code) {
                case '!':
                    return typedMap(kClass, vClass, usingMap, sb);
                case '{':
                    return marshallableAsMap(kClass, vClass, usingMap);
                case '?':
                    return readAllAsMap(kClass, vClass, usingMap);
                default:
                    throw new IORuntimeException("Unexpected code " + (char) code);
            }
        }

        @Nullable
        private <K, V> Map<K, V> typedMap(@NotNull Class<K> kClazz, @NotNull Class<V> vClass, @NotNull Map<K, V> usingMap, @NotNull StringBuilder sb) {
            parseUntil(sb, StopCharTesters.SPACE_STOP);
            @Nullable String str = WireInternal.INTERNER.intern(sb);

            if (("!!null").contentEquals(sb)) {
                text();
                return null;

            } else if (("!" + SEQ_MAP).contentEquals(sb)) {
                consumePadding();
                int start = readCode();
                if (start != '[')
                    throw new IORuntimeException("Unsupported start of sequence : " + (char) start);
                do {
                    marshallable(r -> {
                        @Nullable final K k = r.read(() -> "key")
                                .object(kClazz);
                        @Nullable final V v = r.read(() -> "value")
                                .object(vClass);
                        usingMap.put(k, v);
                    });
                } while (hasNextSequenceItem());
                return usingMap;

            } else {
                throw new IORuntimeException("Unsupported type :" + str);
            }
        }

        @Override
        public <K extends ReadMarshallable, V extends ReadMarshallable> void typedMap(@NotNull Map<K, V> usingMap) {
            consumePadding();
            usingMap.clear();

            @NotNull StringBuilder sb = acquireStringBuilder();
            if (peekCode() == '!') {
                parseUntil(sb, StopCharTesters.SPACE_STOP);
                @Nullable String str = WireInternal.INTERNER.intern(sb);
                if (SEQ_MAP.contentEquals(sb)) {
                    while (hasNext()) {
                        sequence(this, (o, s) -> s.marshallable(r -> {
                            try {
                                @Nullable @SuppressWarnings("unchecked") final K k = r.read(() -> "key").typedMarshallable();
                                @Nullable @SuppressWarnings("unchecked") final V v = r.read(() -> "value").typedMarshallable();
                                usingMap.put(k, v);
                            } catch (Exception e) {
                                Jvm.warn().on(getClass(), e);
                            }
                        }));
                    }
                } else {
                    throw new IORuntimeException("Unsupported type " + str);
                }
            }
        }

        @Override
        public boolean bool() {
            consumePadding();
            @NotNull StringBuilder sb = acquireStringBuilder();
            if (textTo(sb) == null)
                throw new NullPointerException("value is null");

            return ObjectUtils.isTrue(sb);
        }

        public byte int8() {
            long l = int64();
            if (l > Byte.MAX_VALUE || l < Byte.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Byte.MAX_VALUE/MIN_VALUE");
            return (byte) l;
        }

        public short int16() {
            long l = int64();
            if (l > Short.MAX_VALUE || l < Short.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Short.MAX_VALUE/MIN_VALUE");
            return (short) l;
        }

        public int int32() {
            long l = int64();
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE)
                throw new IllegalStateException("value=" + l + ", is greater or less than Integer.MAX_VALUE/MIN_VALUE");
            return (int) l;
        }

        public int uint16() {
            long l = int64();
            if (l > Integer.MAX_VALUE || l < 0)
                throw new IllegalStateException("value=" + l + ", is greater or less than Integer" +
                        ".MAX_VALUE/ZERO");
            return (int) l;
        }

        @Override
        public long int64() {
            consumePadding();
            valueIn.skipType();
            long l = getALong();
            checkRewind();
            return l;
        }

        public void checkRewind() {
            int ch = bytes.readUnsignedByte(bytes.readPosition() - 1);
            if (ch == ':' || ch == '}' || ch == ']')
                bytes.readSkip(-1);
        }

        @Override
        public double float64() {
            consumePadding();
            valueIn.skipType();
            final double v = bytes.parseDouble();
            checkRewind();
            return v;
        }

        private void skipType() {
            long peek = bytes.peekUnsignedByte();
            if (peek == '!') {
                @NotNull StringBuilder sb = acquireStringBuilder();
                parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
                consumePadding();
            }
        }

        @Override
        public float float32() {

            double d = float64();
            if ((double) (((float) d)) != d)
                throw new IllegalStateException("value=" + d + " can not be represented as a float");

            return (float) d;
        }

        /**
         * @return true if !!null "", if {@code true} reads the !!null "" up to the next STOP, if
         * {@code false} no  data is read  ( data is only peaked if {@code false} )
         */
        public boolean isNull() {
            consumePadding();

            if (peekStringIgnoreCase("!!null \"\"")) {
                bytes.readSkip("!!null \"\"".length());
                // discard the text after it.
                //  text(acquireStringBuilder());
                return true;
            }

            return false;
        }

        public Object objectWithInferredType(Object using, @NotNull SerializationStrategy strategy, Class type) {
            consumePadding();
            @Nullable Object o = objectWithInferredType0(using, strategy, type);
            consumePadding();
            int code = peekCode();
            if (code == ':') {
                return readRestOfMap(using, o);
            }
            return o;
        }

        @NotNull
        Object readRestOfMap(Object using, Object o) {
            readCode();
            consumePadding();
            @Nullable Object value = objectWithInferredType0(using, SerializationStrategies.ANY_OBJECT, Object.class);
            @NotNull Map map = using instanceof Map ? (Map) using : new LinkedHashMap();
            map.put(o, value);
            readAllAsMap(Object.class, Object.class, map);
            return map;
        }

        @Nullable
        Object objectWithInferredType0(Object using, @NotNull SerializationStrategy strategy, Class type) {
            int code = peekCode();
            switch (code) {
                case '?':
                    return map(Object.class, Object.class, (Map) using);
                case '!':
                    return object(using, type);
                case '-':
                    if (bytes.readByte(bytes.readPosition() + 1) == ' ')
                        return readList(indentation(), null);
                    return valueIn.readNumber();
                case '[':
                    return readSequence(strategy.type());
                case '{':
                    return valueIn.marshallableAsMap(Object.class, Object.class);
                case ']':
                    throw new IORuntimeException("Unexpected ] at " + bytes.toDebugString(32));
                case '}':
                    throw new IORuntimeException("Unexpected } at " + bytes.toDebugString(32));
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '+':
                    return valueIn.readNumber();
            }

            if (using instanceof Bytes)
                return valueIn.textTo((Bytes) using);

            if (using instanceof StringBuilder)
                return valueIn.textTo((StringBuilder) using);

            @Nullable String text = valueIn.text();
            if (text == null || Enum.class.isAssignableFrom(strategy.type()))
                return text;
            switch (text) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                default:
                    return text;
            }
        }

        @Nullable
        protected Object readNumber() {
            @Nullable String s = text();
            @Nullable String ss = s;
            if (s == null || s.length() > 40)
                return s;

            if (s.contains("_"))
                ss = s.replace("_", "");
            try {
                return Long.decode(ss);
            } catch (NumberFormatException fallback) {
            }
            try {
                return Double.parseDouble(ss);
            } catch (NumberFormatException fallback) {
            }
            try {
                if (s.length() == 7 && s.charAt(1) == ':')
                    return LocalTime.parse("0" + s);
                if (s.length() == 8 && s.charAt(2) == ':')
                    return LocalTime.parse(s);
            } catch (DateTimeParseException fallback) {
            }
            try {
                if (s.length() == 10)
                    return LocalDate.parse(s);
            } catch (DateTimeParseException fallback) {
            }
            try {
                if (s.length() >= 22)
                    return ZonedDateTime.parse(s);
            } catch (DateTimeParseException fallback) {
            }
            return s;
        }

        @NotNull
        private Object readSequence(@NotNull Class clazz) {
            if (clazz == Object[].class || clazz == Object.class) {
                //todo should this use reflection so that all array types can be handled
                @NotNull List<Object> list = new ArrayList<>();
                sequence(list, (l, v) -> {
                    while (v.hasNextSequenceItem()) {
                        l.add(v.object(Object.class));
                    }
                });
                return clazz == Object[].class ? list.toArray() : list;
            } else if (clazz == String[].class) {
                @NotNull List<String> list = new ArrayList<>();
                sequence(list, (l, v) -> {
                    while (v.hasNextSequenceItem()) {
                        l.add(v.text());
                    }
                });
                return list.toArray(new String[0]);
            } else if (clazz == List.class) {
                @NotNull List<String> list = new ArrayList<>();
                sequence(list, (l, v) -> {
                    while (v.hasNextSequenceItem()) {
                        l.add(v.text());
                    }
                });
                return list;
            } else if (clazz == Set.class) {
                @NotNull Set<String> list = new HashSet<>();
                sequence(list, (l, v) -> {
                    while (v.hasNextSequenceItem()) {
                        l.add(v.text());
                    }
                });
                return list;
            } else {
                throw new UnsupportedOperationException("Arrays of type "
                        + clazz + " not supported.");
            }
        }

        private Object typedObject() {
            readCode();
            @NotNull StringBuilder sb = acquireStringBuilder();
            parseUntil(sb, TextStopCharTesters.END_OF_TYPE);
            if (StringUtils.isEqual(sb, "!null")) {
                text();
                return null;
            }
            final Class clazz2;
            try {
                clazz2 = classLookup().forName(sb);
            } catch (ClassNotFoundException e) {
                throw new IORuntimeException(e);
            }
            return object(null, clazz2);
        }

        @Override
        public String toString() {
            return TextWire.this.toString();
        }
    }
}
