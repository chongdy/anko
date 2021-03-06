/*
 * Copyright 2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.anko.db

import android.database.Cursor
import android.database.sqlite.SQLiteException
import org.jetbrains.anko.AnkoException
import org.jetbrains.anko.internals.AnkoInternals
import java.lang.reflect.Modifier
import java.util.*

interface RowParser<T> {
    fun parseRow(columns: Array<Any?>): T
}

interface MapRowParser<T> {
    fun parseRow(columns: Map<String, Any?>): T
}

private class SingleColumnParser<T> : RowParser<T> {
    override fun parseRow(columns: Array<Any?>): T {
        if (columns.size != 1)
            throw SQLiteException("Invalid row: row for SingleColumnParser must contain exactly one column")
        @Suppress("UNCHECKED_CAST")
        return columns[0] as T
    }
}

private class ScalarColumnParser<R, T>(val modifier: ((R) -> T)? = null) : RowParser<T> {
    override fun parseRow(columns: Array<Any?>): T {
        if (columns.size != 1)
            throw SQLiteException("Invalid row: row for SingleColumnParser must contain exactly one column")
        @Suppress("UNCHECKED_CAST", "UNNECESSARY_NOT_NULL_ASSERTION")
        return if (modifier != null)
            modifier!!(columns[0] as R)
        else
            columns[0] as T
    }
}

val ShortParser: RowParser<Short> = ScalarColumnParser<Long, Short> { it.toShort() }
val IntParser: RowParser<Int> = ScalarColumnParser<Long, Int> { it.toInt() }
val LongParser: RowParser<Long> = SingleColumnParser()
val FloatParser: RowParser<Float> = ScalarColumnParser<Double, Float> { it.toFloat() }
val DoubleParser: RowParser<Double> = SingleColumnParser()
val StringParser: RowParser<String> = SingleColumnParser()
val BlobParser: RowParser<ByteArray> = SingleColumnParser()

fun <T: Any> Cursor.parseSingle(parser: RowParser<T>): T = AnkoInternals.useCursor(this) {
    if (count != 1)
        throw SQLiteException("parseSingle accepts only cursors with a single entry")
    moveToFirst()
    return parser.parseRow(readColumnsArray(this))
}

fun <T: Any> Cursor.parseOpt(parser: RowParser<T>): T? = AnkoInternals.useCursor(this) {
    if (count > 1)
        throw SQLiteException("parseSingle accepts only cursors with a single entry or empty cursors")
    if (count == 0)
        return null
    moveToFirst()
    return parser.parseRow(readColumnsArray(this))
}

fun <T: Any> Cursor.parseList(parser: RowParser<T>): List<T> = AnkoInternals.useCursor(this) {
    val list = ArrayList<T>(count)
    moveToFirst()
    while (!isAfterLast) {
        list.add(parser.parseRow(readColumnsArray(this)))
        moveToNext()
    }
    return list
}

fun <T: Any> Cursor.parseSingle(parser: MapRowParser<T>): T = AnkoInternals.useCursor(this) {
    if (count != 1)
        throw SQLiteException("parseSingle accepts only cursors with getCount() == 1")
    moveToFirst()
    return parser.parseRow(readColumnsMap(this))
}

fun <T: Any> Cursor.parseOpt(parser: MapRowParser<T>): T? = AnkoInternals.useCursor(this) {
    if (count > 1)
        throw SQLiteException("parseSingle accepts only cursors with getCount() == 1 or empty cursors")
    if (count == 0)
        return null
    moveToFirst()
    return parser.parseRow(readColumnsMap(this))
}

fun <T: Any> Cursor.parseList(parser: MapRowParser<T>): List<T> = AnkoInternals.useCursor(this) {
    val list = ArrayList<T>(count)
    moveToFirst()
    while (!isAfterLast) {
        list.add(parser.parseRow(readColumnsMap(this)))
        moveToNext()
    }
    return list
}

@Deprecated("Use asSequence() instead", ReplaceWith("asSequence()"))
fun Cursor.sequence(): Sequence<Array<Any?>> {
    return CursorSequence(this)
}

@Deprecated("Use asMapSequence() instead", ReplaceWith("asMapSequence()"))
fun Cursor.mapSequence(): Sequence<Map<String, Any?>> {
    return CursorMapSequence(this)
}

fun Cursor.asSequence(): Sequence<Array<Any?>> {
    return CursorSequence(this)
}

fun Cursor.asMapSequence(): Sequence<Map<String, Any?>> {
    return CursorMapSequence(this)
}

@Suppress("NOTHING_TO_INLINE")
inline fun <reified T: Any> classParser(): RowParser<T> {
    val clazz = T::class.java
    val constructors = clazz.declaredConstructors.filter {
        val types = it.parameterTypes
        !it.isVarArgs && Modifier.isPublic(it.modifiers) &&
            types != null && types.size > 0
    }
    if (constructors.isEmpty())
        throw AnkoException(
            "Can't initialize object parser for ${clazz.canonicalName}, no acceptable constructors found")

    val c = constructors[0]

    for (type in c.parameterTypes) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val valid = when (type) {
            Long::class.java, java.lang.Long::class.java -> true
            Double::class.java, java.lang.Double::class.java -> true
            String::class.java, ByteArray::class.java -> true
            else -> false
        }
        if (!valid)
            throw AnkoException(
                "Invalid argument type ${type.canonicalName} in ${clazz.canonicalName} constructor." +
                "Supported types are: Long, Double, String, Array<Byte>.")
    }

    return object : RowParser<T> {
        override fun parseRow(columns: Array<Any?>): T {
            return c.newInstance(*columns) as T
        }
    }
}

private fun Cursor.getColumnValue(index: Int): Any? {
    if (isNull(index)) return null

    return when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> Unit
    }
}

private fun readColumnsArray(cursor: Cursor): Array<Any?> {
    val count = cursor.columnCount
    val arr = arrayOfNulls<Any?>(count)
    for (i in 0..(count - 1)) {
        arr[i] = cursor.getColumnValue(i)
    }
    return arr
}

private fun readColumnsMap(cursor: Cursor): Map<String, Any?> {
    val count = cursor.columnCount
    val map = hashMapOf<String, Any?>()
    for (i in 0..(count - 1)) {
        map.put(cursor.getColumnName(i), cursor.getColumnValue(i))
    }
    return map
}

private class CursorMapSequence(val cursor: Cursor) : Sequence<Map<String, Any?>> {
    override fun iterator() = CursorMapIterator(cursor)
}

private class CursorSequence(val cursor: Cursor) : Sequence<Array<Any?>> {
    override fun iterator() = CursorIterator(cursor)
}

private class CursorIterator(val cursor: Cursor) : Iterator<Array<Any?>> {
    override fun hasNext() = cursor.position < cursor.count - 1

    override fun next(): Array<Any?> {
        cursor.moveToNext()
        return readColumnsArray(cursor)
    }
}

private class CursorMapIterator(val cursor: Cursor) : Iterator<Map<String, Any?>> {
    override fun hasNext() = cursor.position < cursor.count - 1

    override fun next(): Map<String, Any?> {
        cursor.moveToNext()
        return readColumnsMap(cursor)
    }
}