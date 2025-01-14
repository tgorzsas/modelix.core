/*
 * Copyright (c) 2023.
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

package org.modelix.model.sync.bulk.gradle.test

import org.junit.jupiter.api.Test
import org.xmlunit.builder.Input
import org.xmlunit.xpath.JAXPXPathEngine
import java.io.File
import kotlin.test.assertContentEquals

class PullTest {

    @Test
    fun `nodes were synced to local`() {
        val localModel = File("build/test-repo/solutions/GraphSolution/models/GraphSolution.example.mps").readText()
        val source = Input.fromString(localModel).build()
        val properties = JAXPXPathEngine().selectNodes("model/node/node[@concept='1DmExO']/property", source)

        val actual = properties.map { it.attributes.getNamedItem("value").nodeValue }
        val expected = listOf("X", "Y", "Z", "D", "E")

        assertContentEquals(expected, actual)
    }
}
