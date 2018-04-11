// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.remote.client;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.remote.client.ShellEscaper.escapeString;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ShellEscaper}. */
@RunWith(JUnit4.class)
public class ShellEscaperTest {

  @Test
  public void shellEscape() throws Exception {
    assertThat(escapeString("")).isEqualTo("''");
    assertThat(escapeString("foo")).isEqualTo("foo");
    assertThat(escapeString("foo bar")).isEqualTo("'foo bar'");
    assertThat(escapeString("'foo'")).isEqualTo("''\\''foo'\\'''");
    assertThat(escapeString("\\'foo\\'")).isEqualTo("'\\'\\''foo\\'\\'''");
    assertThat(escapeString("${filename%.c}.o")).isEqualTo("'${filename%.c}.o'");
    assertThat(escapeString("<html!>")).isEqualTo("'<html!>'");
  }

  @Test
  public void escapeJoinAll() throws Exception {
    String actual =
        ShellEscaper.escapeJoinAll(
            Arrays.asList("foo", "@echo:-", "100", "$US", "a b", "\"qu'ot'es\"", "\"quot\"", "\\"));
    assertThat(actual)
        .isEqualTo("foo @echo:- 100 '$US' 'a b' '\"qu'\\''ot'\\''es\"' '\"quot\"' '\\'");
  }
}
