/*
 * Copyright 2022 the original author or authors.
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
package org.openrewrite.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.RewriteTest.toRecipe;

public class MavenDependencyFailuresTest implements RewriteTest {

    @Test
    void unresolvableMavenMetadata() {
        rewriteRun(
          spec -> spec
            .recipe(new UpgradeDependencyVersion("*", "*", "latest.patch", null, null))
            .executionContext(MavenExecutionContextView.view(new InMemoryExecutionContext())
              .setRepositories(List.of(new MavenRepository("jenkins", "https://repo.jenkins-ci.org/public", true, false, true, null, null, null))))
            .recipeExecutionContext(new InMemoryExecutionContext())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1),
          pomXml(
            """
              <project>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
                <dependencies>
                  <dependency>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>credentials</artifactId>
                    <version>2.3.0</version>
                  </dependency>
                  <dependency>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>appio</artifactId>
                    <version>1.3</version>
                  </dependency>
                </dependencies>
              </project>
              """,
            spec -> spec.after(after -> {
                //There should be two errors (one for each failed metadata download)
                assertThat(after.split("Unable to download metadata")).hasSize(3);
                return after;
                })
          )
        );
    }

    @Test
    void unresolvableParent() { // Dad said he was heading to the corner store for cigarettes, and hasn't been resolvable for the past 20 years :'(
        rewriteRun(
          spec -> spec
            .recipe(new UpgradeParentVersion("*", "*", "latest.patch", null))
            .executionContext(MavenExecutionContextView.view(new InMemoryExecutionContext())
              .setRepositories(List.of(new MavenRepository("jenkins", "https://repo.jenkins-ci.org/public", true, false, true, null, null, null))))
            .recipeExecutionContext(new InMemoryExecutionContext())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1),
          pomXml(
            """
              <project>
                <parent>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>credentials</artifactId>
                    <version>2.3.0</version>
                </parent>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
              </project>
              """,
            """
              <project>
                <!--~~(Unable to download metadata. Tried repositories:
              https://repo.maven.apache.org/maven2: HTTP 404)~~>--><parent>
                    <groupId>org.jenkins-ci.plugins</groupId>
                    <artifactId>credentials</artifactId>
                    <version>2.3.0</version>
                </parent>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
              </project>
              """
          )
        );
    }

    @Test
    void unresolvableTransitiveDependency(@TempDir Path localRepository) throws IOException {
        // it's hard to simulate a transitive dependency failure since Maven Central validates
        // transitive dependency resolvability on publishing.
        //
        // this test creates a pom in a local repository that is resolvable at parse time because
        // it has no dependencies, but becomes unresolvable when the model is refreshed because it
        // is overwritten on disk with dependencies that don't exist.
        Path localPom = localRepository.resolve("com/bad/bad-artifact/1/bad-artifact-1.pom");
        assertThat(localPom.getParent().toFile().mkdirs()).isTrue();
        Files.write(localPom,
          //language=xml
          """
             <project>
               <groupId>com.bad</groupId>
               <artifactId>bad-artifact</artifactId>
               <version>1</version>
             </project>
            """.getBytes()
        );

        MavenRepository mavenLocal = new MavenRepository("local", localRepository.toUri().toString(), true, false, true, null, null, null);

        rewriteRun(
          spec -> spec
            .recipe(updateModel())
            .executionContext(MavenExecutionContextView.view(new InMemoryExecutionContext())
              .setLocalRepository(mavenLocal)
            )
            .recipeExecutionContext(MavenExecutionContextView.view(new InMemoryExecutionContext())
              .setLocalRepository(mavenLocal)
              .setPomCache(new InMemoryMavenPomCache())
            )
            .cycles(1)
            .expectedCyclesThatMakeChanges(1),
          pomXml(
            """
              <project>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
                <dependencies>
                  <dependency>
                    <groupId>com.bad</groupId>
                    <artifactId>bad-artifact</artifactId>
                    <version>1</version>
                  </dependency>
                </dependencies>
              </project>
              """,
            """
              <project>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
                <dependencies>
                  <!--~~(doesnotexist:doesnotexist:1 failed. Unable to download POM. Tried repositories:
              https://repo.maven.apache.org/maven2: HTTP 404)~~>--><!--~~(doesnotexist:another:1 failed. Unable to download POM. Tried repositories:
              https://repo.maven.apache.org/maven2: HTTP 404)~~>--><dependency>
                    <groupId>com.bad</groupId>
                    <artifactId>bad-artifact</artifactId>
                    <version>1</version>
                  </dependency>
                </dependencies>
              </project>
              """,
            spec -> spec.beforeRecipe(maven -> {
                // make the local pom bad before running the recipe
                Files.write(localPom,
                  //language=xml
                  """
                     <project>
                       <groupId>com.bad</groupId>
                       <artifactId>bad-artifact</artifactId>
                       <version>1</version>
                       <dependencies>
                         <dependency>
                           <groupId>doesnotexist</groupId>
                           <artifactId>doesnotexist</artifactId>
                           <version>1</version>
                         </dependency>
                         <dependency>
                           <groupId>doesnotexist</groupId>
                           <artifactId>another</artifactId>
                           <version>1</version>
                         </dependency>
                       </dependencies>
                     </project>
                    """.getBytes()
                );
            })
          )
        );
    }

    @Test
    void unresolvableDependency() {
        assertThatThrownBy(() ->
          rewriteRun(
            pomXml(
              """
                <project>
                  <groupId>com.mycompany.app</groupId>
                  <artifactId>my-app</artifactId>
                  <version>1</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>doesnotexist</version>
                    </dependency>
                    <dependency>
                      <groupId>com.google.another</groupId>
                      <artifactId>${doesnotexist}</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.google.yetanother</groupId>
                      <artifactId>${doesnotexist}</artifactId>
                      <version>1</version>
                    </dependency>
                  </dependencies>
                </project>
                """
            )
          ))
          .isInstanceOf(UncheckedMavenDownloadingException.class)
          .satisfies(t -> {
              Xml.Document pom = ((UncheckedMavenDownloadingException) t).getPomWithWarnings();
              //language=xml
              assertThat(pom.printAllTrimmed()).isEqualTo(
                """
                  <project>
                    <groupId>com.mycompany.app</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1</version>
                    <dependencies>
                      <!--~~(Unable to download POM. Tried repositories:
                  https://repo.maven.apache.org/maven2: HTTP 404)~~>--><dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>doesnotexist</version>
                      </dependency>
                      <!--~~(No version provided)~~>--><dependency>
                        <groupId>com.google.another</groupId>
                        <artifactId>${doesnotexist}</artifactId>
                      </dependency>
                      <!--~~(Could not resolve property)~~>--><dependency>
                        <groupId>com.google.yetanother</groupId>
                        <artifactId>${doesnotexist}</artifactId>
                        <version>1</version>
                      </dependency>
                    </dependencies>
                  </project>
                  """.trim()
              );
          });
    }

    @Test
    void unreachableRepository() {
        rewriteRun(
          pomXml(
            """
              <project>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>1</version>
                <repositories>
                  <repository>
                    <id>unreachable</id>
                    <url>https://unreachable</url>
                  </repository>
                </repositories>
                <dependencies>
                  <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>29.0-jre</version>
                  </dependency>
                </dependencies>
              </project>
              """
          )
        );
    }

    private Recipe updateModel() {
        return toRecipe(() -> new MavenIsoVisitor<>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext executionContext) {
                if (document.getMarkers().findFirst(Markup.class).isEmpty()) {
                    maybeUpdateModel();
                }
                return document;
            }
        });
    }
}
