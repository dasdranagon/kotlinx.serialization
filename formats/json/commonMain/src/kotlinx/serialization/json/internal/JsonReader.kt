/*
 * Copyright 2017-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json.internal

import kotlinx.serialization.json.internal.CharMappings.CHAR_TO_TOKEN
import kotlinx.serialization.json.internal.CharMappings.ESCAPE_2_CHAR
import kotlin.jvm.*

internal const val lenientHint = "Use 'isLenient = true' in 'Json {}` builder to accept non-compliant JSON."
internal const val coerceInputValuesHint =
    "Use 'coerceInputValues = true' in 'Json {}` builder to coerce nulls to default values."
internal const val specialFlowingValuesHint =
    "It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'"
internal const val ignoreUnknownKeysHint = "Use 'ignoreUnknownKeys = true' in 'Json {}' builder to ignore unknown keys."
internal const val allowStructuredMapKeysHint =
    "Use 'allowStructuredMapKeys = true' in 'Json {}' builder to convert such maps to [key1, value1, key2, value2,...] arrays."

// special strings
internal const val NULL = "null"

// special chars
internal const val COMMA = ','
internal const val COLON = ':'
internal const val BEGIN_OBJ = '{'
internal const val END_OBJ = '}'
internal const val BEGIN_LIST = '['
internal const val END_LIST = ']'
internal const val STRING = '"'
internal const val STRING_ESC = '\\'

internal const val INVALID = 0.toChar()
internal const val UNICODE_ESC = 'u'

// token classes
internal const val TC_OTHER: Byte = 0
internal const val TC_STRING: Byte = 1
internal const val TC_STRING_ESC: Byte = 2
internal const val TC_WHITESPACE: Byte = 3
internal const val TC_COMMA: Byte = 4
internal const val TC_COLON: Byte = 5
internal const val TC_BEGIN_OBJ: Byte = 6
internal const val TC_END_OBJ: Byte = 7
internal const val TC_BEGIN_LIST: Byte = 8
internal const val TC_END_LIST: Byte = 9
internal const val TC_NULL: Byte = 10
internal const val TC_INVALID: Byte = 11
internal const val TC_EOF: Byte = 12

// mapping from chars to token classes
private const val CTC_MAX = 0x7e

// mapping from escape chars real chars
private const val ESC2C_MAX = 0x75

// object instead of @SharedImmutable because there is mutual initialization in [initC2ESC] and [initC2TC]
internal object CharMappings {
    @JvmField
    val ESCAPE_2_CHAR = CharArray(ESC2C_MAX)

    @JvmField
    val CHAR_TO_TOKEN = ByteArray(CTC_MAX)

    init {
        initEscape()
        initCharToToken()
    }

    private fun initEscape() {
        for (i in 0x00..0x1f) {
            initC2ESC(i, UNICODE_ESC)
        }

        initC2ESC(0x08, 'b')
        initC2ESC(0x09, 't')
        initC2ESC(0x0a, 'n')
        initC2ESC(0x0c, 'f')
        initC2ESC(0x0d, 'r')
        initC2ESC('/', '/')
        initC2ESC(STRING, STRING)
        initC2ESC(STRING_ESC, STRING_ESC)
    }

    private fun initCharToToken() {
        for (i in 0..0x20) {
            initC2TC(i, TC_INVALID)
        }

        initC2TC(0x09, TC_WHITESPACE)
        initC2TC(0x0a, TC_WHITESPACE)
        initC2TC(0x0d, TC_WHITESPACE)
        initC2TC(0x20, TC_WHITESPACE)
        initC2TC(COMMA, TC_COMMA)
        initC2TC(COLON, TC_COLON)
        initC2TC(BEGIN_OBJ, TC_BEGIN_OBJ)
        initC2TC(END_OBJ, TC_END_OBJ)
        initC2TC(BEGIN_LIST, TC_BEGIN_LIST)
        initC2TC(END_LIST, TC_END_LIST)
        initC2TC(STRING, TC_STRING)
        initC2TC(STRING_ESC, TC_STRING_ESC)
    }

    private fun initC2ESC(c: Int, esc: Char) {
        if (esc != UNICODE_ESC) ESCAPE_2_CHAR[esc.toInt()] = c.toChar()
    }

    private fun initC2ESC(c: Char, esc: Char) = initC2ESC(c.toInt(), esc)

    private fun initC2TC(c: Int, cl: Byte) {
        CHAR_TO_TOKEN[c] = cl
    }

    private fun initC2TC(c: Char, cl: Byte) = initC2TC(c.toInt(), cl)
}

internal fun charToTokenClass(c: Char) = if (c.toInt() < CTC_MAX) CHAR_TO_TOKEN[c.toInt()] else TC_OTHER

internal fun escapeToChar(c: Int): Char = if (c < ESC2C_MAX) ESCAPE_2_CHAR[c] else INVALID

// Streaming JSON reader
internal class JsonReader(private val source: String) {

    @JvmField
    var currentPosition: Int = 0 // position in source

    // TODO this one should be built-in assert
    public val isDone: Boolean get() = consumeNextToken() == TC_EOF

    fun tryConsumeComma(): Boolean {
        skipWhitespaces()
        val current = currentPosition
        // TODO throw EOF?
        if (current == source.length) return false
        if (charToTokenClass(source[current]) == TC_COMMA) {
            ++currentPosition
            return true
        }
        return false
    }

    fun canConsumeValue(): Boolean {
        while (currentPosition < source.length) {
            val ch = source[currentPosition]
            return when (charToTokenClass(ch)) {
                TC_WHITESPACE -> {
                    ++currentPosition
                    continue
                }
                TC_BEGIN_LIST, TC_BEGIN_OBJ, TC_OTHER, TC_STRING, TC_NULL -> {
                    true
                }
                else -> {
                    false
                }
            }
        }
        return false
    }

    // updated by nextToken
    // TODO remove
    private var tokenPosition: Int = 0

    // update by nextString/nextLiteral
    private var offset = -1 // when offset >= 0 string is in source, otherwise in buf
    private var length = 0 // length of string
    private var buf = CharArray(16) // only used for strings with escapes

    inline fun consumeNextToken(expected: Byte, errorMessage: (Char) -> String): Byte {
        val token = consumeNextToken()
        if (token != expected) {
            fail(errorMessage(token.toChar()), currentPosition)
        }
        return token
    }

    fun peekNextToken(): Byte {
        val source = source
        while (currentPosition < source.length) {
            val ch = source[currentPosition]
            return when (val tc = charToTokenClass(ch)) {
                TC_WHITESPACE -> {
                    ++currentPosition
                    continue
                }
                else -> tc
            }
        }
        return TC_EOF
    }

    fun consumeNextToken(): Byte {
        val source = source
        while (currentPosition < source.length) {
            val ch = source[currentPosition++]
            return when (val tc = charToTokenClass(ch)) {
                TC_WHITESPACE -> continue
                else -> tc
            }
        }
        return TC_EOF
    }

    /**
     * TODO explain
     */
    fun tryConsumeNotNull(): Boolean {
        skipWhitespaces()
        val current = currentPosition
        if (source.length - current < 4) return true
        for (i in 0..3) {
            if (NULL[i] != source[current + i]) return true
        }
        currentPosition = current + 4
        return false
    }

    private fun skipWhitespaces() {
        var current = currentPosition
        // Skip whitespaces
        while (charToTokenClass(source[current]) == TC_WHITESPACE) {
            ++current
        }
        currentPosition = current
    }

    //        var current = currentPosition
//        if (current >= source.length) {
//            fail("EOF", currentPosition)
//        }

    // TODO consider reusing string builder via setLength(0)
//        val sb = StringBuilder()
//        var currentChar = source[current]
//        while (currentChar != STRING) {
//            if (currentChar == STRING_ESC) {
//                TODO() // fail for a while
//            } else if (++current >= source.length) {
//                TODO() // TODO better exception message
////                fail("EOF", currentPosition)
//            }
//            sb.append(currentChar)
//            currentChar = source[current]
//        }
//        currentPosition = current + 1 // Consume last quotation mark as well
//        return sb.toString()
    // TODO this is a strict version
    fun consumeString(): String {
        consumeNextToken(TC_STRING) { "Expected quoted string" }
        // TODO verify that this approach is actually faster
        // Fast path:
        var currentPosition = currentPosition
//        val closingQuote = source.indexOf('"', currentPosition)


        val startPosition = currentPosition - 1
        var lastPosition = currentPosition
        length = 0
        while (source[currentPosition] != STRING) {
            if (source[currentPosition] == STRING_ESC) {
                appendRange(source, lastPosition, currentPosition)
                val newPosition = appendEsc(source, currentPosition + 1)
                currentPosition = newPosition
                lastPosition = newPosition
            } else if (++currentPosition >= source.length) {
                fail("EOF", currentPosition)
            }
        }

        val string = if (lastPosition == startPosition + 1) {
            // there was no escaped chars
            source.substring(lastPosition, currentPosition)
        } else {
            // some escaped chars were there
            appendRange(source, lastPosition, currentPosition)
            buf.concatToString(0, length)
        }
        this.currentPosition = currentPosition + 1
        return string
    }

    fun consumeKeyString(): String {
        consumeNextToken(TC_STRING) { "Expected quoted string" }
        // TODO verify that this approach is actually faster
        // Fast path:
        val currentPosition = currentPosition
        val closingQuote = source.indexOf('"', currentPosition)
        if (closingQuote == -1) // TODO error
            TODO()

        /*
         * TODO:
         * 1) measure the indexOf after the substring vs loop
         * 2) measure backwards iteration for better cache locality
         */
        for (i in currentPosition until closingQuote) {
            if (source[i] == '\\') TODO()
        }
        this.currentPosition = closingQuote + 1
        return source.substring(currentPosition, closingQuote)
    }

    private fun nextString(source: String, startPosition: Int) {
        tokenPosition = startPosition
        length = 0 // in buffer
        var currentPosition = startPosition + 1 // skip starting "
        // except if the input ends
        if (currentPosition >= source.length) {
            fail("EOF", currentPosition)
        }
        var lastPosition = currentPosition
        while (source[currentPosition] != STRING) {
            if (source[currentPosition] == STRING_ESC) {
                appendRange(source, lastPosition, currentPosition)
                val newPosition = appendEsc(source, currentPosition + 1)
                currentPosition = newPosition
                lastPosition = newPosition
            } else if (++currentPosition >= source.length) {
                fail("EOF", currentPosition)
            }
        }
        if (lastPosition == startPosition + 1) {
            // there was no escaped chars
            offset = lastPosition
            this.length = currentPosition - lastPosition
        } else {
            // some escaped chars were there
            appendRange(source, lastPosition, currentPosition)
            this.offset = -1
        }
        this.currentPosition = currentPosition + 1
//        tokenClass = TC_STRING
    }

    // Allows to consume unquoted string
    fun consumeStringLenient(): String {
        // TODO figure out all the EOFs
        skipWhitespaces()
        var current = currentPosition
        // Skip leading quotation mark
        if (source[current] == '"') ++current
        while (current < source.length && charToTokenClass(source[current]) == TC_OTHER) {
            current++
        }
        val result = source.substring(currentPosition, current)
        // Skip trailing quotation
        if (current == source.length) {
            currentPosition = current
        } else {
            currentPosition = if (source[current] == '"') ++current else current

        }
        return result
    }

    private fun nextLiteral(source: String, startPos: Int) {
        tokenPosition = startPos
        offset = startPos
        var currentPosition = startPos
        while (currentPosition < source.length && charToTokenClass(source[currentPosition]) == TC_OTHER) {
            currentPosition++
        }
        this.currentPosition = currentPosition
        length = currentPosition - offset
//        tokenClass = if (rangeEquals(source, offset, length, NULL)) TC_NULL else TC_OTHER
    }


    // TODO doesn't work
    fun peekString(isLenient: Boolean): String? {
//        return if (tokenClass != TC_STRING && (!isLenient || tokenClass != TC_OTHER)) null
//        else takeStringInternal(advance = false)
        TODO()
    }

    private fun takeStringInternal(advance: Boolean = true): String {
        val prevStr = if (offset < 0)
            buf.concatToString(0, 0 + length) else
            source.substring(offset, offset + length)
        if (advance) nextToken()
        return prevStr
    }

    private fun append(ch: Char) {
        if (length >= buf.size) buf = buf.copyOf(2 * buf.size)
        buf[length++] = ch
    }

    // initializes buf usage upon the first encountered escaped char
    private fun appendRange(source: String, fromIndex: Int, toIndex: Int) {
        val addLen = toIndex - fromIndex
        val oldLen = length
        val newLen = oldLen + addLen
        if (newLen > buf.size) buf = buf.copyOf(newLen.coerceAtLeast(2 * buf.size))
        for (i in 0 until addLen) buf[oldLen + i] = source[fromIndex + i]
        length += addLen
    }

    private fun appendEsc(source: String, startPosition: Int): Int {
        var currentPosition = startPosition
        require(currentPosition < source.length, currentPosition) { "Unexpected EOF after escape character" }
        val currentChar = source[currentPosition++]
        if (currentChar == UNICODE_ESC) {
            return appendHex(source, currentPosition)
        }

        val c = escapeToChar(currentChar.toInt())
        require(c != INVALID, currentPosition) { "Invalid escaped char '$currentChar'" }
        append(c)
        return currentPosition
    }

    private fun appendHex(source: String, startPos: Int): Int {
        var curPos = startPos
        append(
            ((fromHexChar(source, curPos++) shl 12) +
                    (fromHexChar(source, curPos++) shl 8) +
                    (fromHexChar(source, curPos++) shl 4) +
                    fromHexChar(source, curPos++)).toChar()
        )
        return curPos
    }

    fun skipElement() {
        TODO()
//        if (tokenClass != TC_BEGIN_OBJ && tokenClass != TC_BEGIN_LIST) {
//            nextToken()
//            return
//        }
//        val tokenStack = mutableListOf<Byte>()
//        do {
//            when (tokenClass) {
//                TC_BEGIN_LIST, TC_BEGIN_OBJ -> tokenStack.add(tokenClass)
//                TC_END_LIST -> {
//                    if (tokenStack.last() != TC_BEGIN_LIST) throw JsonDecodingException(
//                        currentPosition,
//                        "found ] instead of }",
//                        source
//                    )
//                    tokenStack.removeAt(tokenStack.size - 1)
//                }
//                TC_END_OBJ -> {
//                    if (tokenStack.last() != TC_BEGIN_OBJ) throw JsonDecodingException(
//                        currentPosition,
//                        "found } instead of ]",
//                        source
//                    )
//                    tokenStack.removeAt(tokenStack.size - 1)
//                }
//            }
//            nextToken()
//        } while (tokenStack.isNotEmpty())
    }

    override fun toString(): String {
        return "JsonReader(source='$source', currentPosition=$currentPosition, tokenPosition=$tokenPosition, offset=$offset)"
    }

    public fun fail(message: String, position: Int = currentPosition): Nothing {
        throw JsonDecodingException(position, message, source)
    }

    internal inline fun require(condition: Boolean, position: Int = currentPosition, message: () -> String) {
        if (!condition) fail(message(), position)
    }

    private fun fromHexChar(source: String, currentPosition: Int): Int {
        require(currentPosition < source.length, currentPosition) { "Unexpected EOF during unicode escape" }
        return when (val curChar = source[currentPosition]) {
            in '0'..'9' -> curChar.toInt() - '0'.toInt()
            in 'a'..'f' -> curChar.toInt() - 'a'.toInt() + 10
            in 'A'..'F' -> curChar.toInt() - 'A'.toInt() + 10
            else -> fail("Invalid toHexChar char '$curChar' in unicode escape")
        }
    }

    // TODO this one lookaheads too much
    fun nextToken() {
        val source = source
        var currentPosition = currentPosition
        while (currentPosition < source.length) {
            val ch = source[currentPosition]
            when (val tc = charToTokenClass(ch)) {
                TC_WHITESPACE -> currentPosition++ // skip whitespace
                TC_OTHER -> {
                    nextLiteral(source, currentPosition)
                    return
                }
                TC_STRING -> {
                    nextString(source, currentPosition)
                    return
                }
                else -> {
                    this.tokenPosition = currentPosition
//                    this.tokenClass = tc
                    this.currentPosition = currentPosition + 1
                    return
                }
            }
        }

        tokenPosition = currentPosition
//        tokenClass = TC_EOF
    }
}
