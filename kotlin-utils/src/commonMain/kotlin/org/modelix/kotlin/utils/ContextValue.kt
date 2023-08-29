/*
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
package org.modelix.kotlin.utils

expect class ContextValue<E> {

    constructor()
    constructor(defaultValue: E)

    fun getValue(): E
    fun getValueOrNull(): E?
    fun getAllValues(): List<E>
    fun <T> computeWith(newValue: E, body: () -> T): T

    suspend fun <T> runInCoroutine(newValue: E, body: suspend () -> T): T
}

fun <E, T> ContextValue<E>.offer(value: E, body: () -> T): T {
    return if (getAllValues().isEmpty()) {
        computeWith(value, body)
    } else {
        body()
    }
}
