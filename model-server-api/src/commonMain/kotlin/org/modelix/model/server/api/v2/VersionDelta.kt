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
package org.modelix.model.server.api.v2

import kotlinx.serialization.Serializable

@Serializable
class VersionDelta(
    val versionHash: String,
    val baseVersionHash: String? = null,
    @Deprecated("use .objectsMap")
    val objects: Set<String> = emptySet(),
    val objectsMap: Map<String, String> = emptyMap(),
)
