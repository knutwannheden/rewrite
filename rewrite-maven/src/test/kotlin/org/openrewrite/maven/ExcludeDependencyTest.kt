/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven

import org.junit.jupiter.api.Test
import org.openrewrite.RefactorVisitorTestForParser
import org.openrewrite.maven.tree.Maven

class ExcludeDependencyTest : RefactorVisitorTestForParser<Maven> {
    override val parser: MavenParser = MavenParser.builder().resolveOptional(false).build()

    private val excludeDependency = ExcludeDependency().apply {
        setGroupId("org.junit.vintage")
        setArtifactId("junit-vintage-engine")
    }

    @Test
    fun excludeJUnitVintageEngineSpringBoot2_3() = assertRefactored(
            visitors = listOf(excludeDependency),
            before = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.3.6.RELEASE</version>
                  </parent>
                  
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
            """,
            after = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.3.6.RELEASE</version>
                  </parent>
                  
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                      <exclusions>
                        <exclusion>
                          <groupId>org.junit.vintage</groupId>
                          <artifactId>junit-vintage-engine</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
            """
    )

    @Test
    fun jUnitVintageEngineDoesntNeedExclusionFromSpringBoot2_4() = assertUnchanged(
            visitors = listOf(excludeDependency),
            before = """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.4.0</version>
                  </parent>
                  
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
            """
    )
}