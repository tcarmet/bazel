// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import java.util.HashMap;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Tuple;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@code native.existing_rule} and {@code native.existing_rules} functions.
 *
 * <p>This class covers the legacy behavior where the {@code
 * --experimental_existing_rules_immutable_view} flag is disabled. The enabled case is covered by
 * the subclass, {@link WithImmutableView}.
 */
@RunWith(JUnit4.class)
public class NativeExistingRulesTest extends BuildViewTestCase {
  private TestStarlarkBuiltin testStarlarkBuiltin; // initialized by createRuleClassProvider()

  // Intended to be overridden by this test case's subclasses. Note that overriding of JUnit's
  // @Before methods is not recommended.
  protected void setupOptions() throws Exception {
    // --noexperimental_existing_rules_immutable_view is the default; set it explicitly for clarity.
    setBuildLanguageOptions("--noexperimental_existing_rules_immutable_view");
  }

  @Before
  public final void setUp() throws Exception {
    setupOptions();
  }

  @StarlarkBuiltin(name = "test")
  private static final class TestStarlarkBuiltin implements StarlarkValue {

    private final Map<String, Object> saved = new HashMap<>();

    @StarlarkMethod(
        name = "save",
        parameters = {
          @Param(name = "name", doc = "Name under which to save the value"),
          @Param(name = "value", doc = "Value to save")
        },
        doc = "Saves a Starlark value for testing from Java")
    public synchronized void save(String name, Object value) {
      saved.put(name, value);
    }
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    testStarlarkBuiltin = new TestStarlarkBuiltin();
    builder.addStarlarkAccessibleTopLevels("test", testStarlarkBuiltin);
    return builder.build();
  }

  private Object getSaved(String name) {
    return testStarlarkBuiltin.saved.get(name);
  }

  @Test
  public void existingRule_handlesSelect() throws Exception {
    scratch.file("test/starlark/BUILD");
    scratch.file(
        "test/starlark/rulestr.bzl", //
        "def rule_dict(name):",
        "  return native.existing_rule(name)");

    scratch.file(
        "test/getrule/BUILD",
        "load('//test/starlark:rulestr.bzl', 'rule_dict')",
        "cc_library(",
        "    name ='x',",
        "    srcs = select({'//conditions:default': []}),",
        ")",
        "rule_dict('x')");

    // Parse the BUILD file, to make sure select() makes it out of native.existing_rule().
    assertThat(getConfiguredTarget("//test/getrule:x")).isNotNull();
  }

  @Test
  public void existingRule_returnsNone() throws Exception {
    scratch.file(
        "test/rulestr.bzl",
        "def test_rule(name, x):",
        "  print(native.existing_rule(x))",
        "  if native.existing_rule(x) == None:",
        "    native.cc_library(name = name)");
    scratch.file(
        "test/BUILD",
        "load('//test:rulestr.bzl', 'test_rule')",
        "test_rule('a', 'does not exist')",
        "test_rule('b', 'BUILD')"); // exists, but as a target and not a rule

    assertThat(getConfiguredTarget("//test:a")).isNotNull();
    assertThat(getConfiguredTarget("//test:b")).isNotNull();
  }

  @Test
  public void existingRule_roundTripsSelect() throws Exception {
    scratch.file(
        "test/existing_rule.bzl",
        "def macro():",
        "  s = select({'//foo': ['//bar']})",
        "  print('Passed: ' + repr(s))",
        "  native.cc_library(name = 'x', srcs = s)",
        "  print('Returned: ' + repr(native.existing_rule('x')['srcs']))",
        // The value returned here should round-trip fine.
        "  native.cc_library(name = 'y', srcs = native.existing_rule('x')['srcs'])");
    scratch.file(
        "test/BUILD",
        "load('//test:existing_rule.bzl', 'macro')",
        "macro()",
        "cc_library(name = 'a', srcs = [])");
    getConfiguredTarget("//test:a");
    assertContainsEvent("Passed: select({\"//foo\": [\"//bar\"]}");
    // The short labels are now in their canonical form, and the sequence is represented as
    // tuple instead of list, but the meaning is unchanged.
    assertContainsEvent("Returned: select({\"//foo:foo\": (\"//bar:bar\",)}");
  }

  @Test
  public void existingRule_shortensLabelsInSamePackage() throws Exception {
    scratch.file(
        "test/existing_rule.bzl",
        "def save_deps():",
        "  r = native.existing_rule('b')",
        "  test.save(\"r['deps']\", r['deps'])");
    scratch.file(
        "test/BUILD",
        "load('//test:existing_rule.bzl', 'save_deps')",
        "cc_library(name = 'a', srcs = [])",
        "cc_binary(name = 'b', deps = ['//test:a'])",
        "save_deps()");
    getConfiguredTarget("//test:b");
    assertThat(Starlark.toIterable(getSaved("r['deps']")))
        .containsExactly(":a"); // as opposed to "//test:a"
  }

  @Test
  public void existingRules_findsRulesAndAttributes() throws Exception {
    scratch.file("test/BUILD");
    scratch.file("test/starlark/BUILD");
    scratch.file(
        "test/starlark/rulestr.bzl",
        "def rule_dict(name):",
        "  return native.existing_rule(name)",
        "def rules_dict():",
        "  return native.existing_rules()",
        "def nop(ctx):",
        "  pass",
        "nop_rule = rule(attrs = {'x': attr.label()}, implementation = nop)",
        "def test_save(name, value):",
        "  test.save(name, value)");

    scratch.file(
        "test/getrule/BUILD",
        "load('//test/starlark:rulestr.bzl', 'rules_dict', 'rule_dict', 'nop_rule', 'test_save')",
        "genrule(name = 'a', outs = ['a.txt'], ",
        "        licenses = ['notice'],",
        "        output_to_bindir = False,",
        "        tools = [ '//test:bla' ], cmd = 'touch $@')",
        "nop_rule(name = 'c', x = ':a')",
        "rlist = rules_dict()",
        "test_save('all_str', [rlist['a']['kind'], rlist['a']['name'],",
        "                      rlist['c']['kind'], rlist['c']['name']])",
        "adict = rule_dict('a')",
        "cdict = rule_dict('c')",
        "test_save('a_str', [adict['kind'], adict['name'], adict['outs'][0], adict['tools'][0]])",
        "test_save('c_str', [cdict['kind'], cdict['name'], cdict['x']])",
        "test_save('adict.keys()', adict.keys())");

    getConfiguredTarget("//test/getrule:BUILD");
    assertThat(Starlark.toIterable(getSaved("all_str")))
        .containsExactly("genrule", "a", "nop_rule", "c").inOrder();
    assertThat(Starlark.toIterable(getSaved("a_str")))
        .containsExactly("genrule", "a", ":a.txt", "//test:bla").inOrder();
    assertThat(Starlark.toIterable(getSaved("c_str")))
        .containsExactly("nop_rule", "c", ":a")
        .inOrder();
    assertThat(Starlark.toIterable(getSaved("adict.keys()")))
        .containsAtLeast(
            "name",
            "visibility",
            "transitive_configs",
            "tags",
            "generator_name",
            "generator_function",
            "generator_location",
            "features",
            "compatible_with",
            "target_compatible_with",
            "restricted_to",
            "srcs",
            "tools",
            "toolchains",
            "outs",
            "cmd",
            "output_to_bindir",
            "local",
            "message",
            "executable",
            "stamp",
            "heuristic_label_expansion",
            "kind");
  }

  @Test
  public void existingRule_returnsObjectWithCorrectMutability() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('inc.bzl', 'f')", //
        "f()");
    scratch.file(
        "test/inc.bzl", //
        "def f():",
        "  native.config_setting(name='x', define_values={'key': 'value'})",
        "  r = native.existing_rule('x')",
        "  r['no_such_attribute'] = 'foo'",
        "  r['define_values']['key'] = 123"); // mutate the dict

    assertThat(getConfiguredTarget("//test:BUILD")).isNotNull(); // no error on mutation
  }

  @Test
  public void existingRule_returnsDictLikeObject() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('inc.bzl', 'f')", //
        "f()");
    scratch.file(
        "test/inc.bzl", //
        "def f():",
        "  native.config_setting(name='x', define_values={'key': 'value'})",
        "  r = native.existing_rule('x')",
        "  print('r == %s' % repr(r))",
        "  test.save('[key for key in r]', [key for key in r])",
        "  test.save('list(r)', list(r))",
        "  test.save('r.keys()', r.keys())",
        "  test.save('r.values()', r.values())",
        "  test.save('r.items()', r.items())",
        "  test.save(\"r['define_values']\", r['define_values'])",
        "  test.save(\"r.get('define_values', 123)\", r.get('define_values', 123))",
        "  test.save(\"r.get('invalid_attr', 123)\", r.get('invalid_attr', 123))",
        "  test.save(\"'define_values' in r\", 'define_values' in r)",
        "  test.save(\"'invalid_attr' in r\", 'invalid_attr' in r)");

    Dict<?, ?> expectedDefineValues = Dict.builder().put("key", "value").buildImmutable();
    assertThat(getConfiguredTarget("//test:BUILD")).isNotNull(); // no error
    assertThat(Starlark.toIterable(getSaved("[key for key in r]")))
        .containsAtLeast("define_values", "name", "kind");
    assertThat(Starlark.toIterable(getSaved("list(r)")))
        .containsAtLeast("define_values", "name", "kind");
    assertThat(Starlark.toIterable(getSaved("r.keys()")))
        .containsAtLeast("define_values", "name", "kind");
    assertThat(Starlark.toIterable(getSaved("r.values()")))
        .containsAtLeast(expectedDefineValues, "x", "config_setting");
    assertThat(Starlark.toIterable(getSaved("r.items()")))
        .containsAtLeast(
            Tuple.of("define_values", expectedDefineValues),
            Tuple.of("name", "x"),
            Tuple.of("kind", "config_setting"));
    assertThat(getSaved("r['define_values']")).isEqualTo(expectedDefineValues);
    assertThat(getSaved("r.get('define_values', 123)")).isEqualTo(expectedDefineValues);
    assertThat(getSaved("r.get('invalid_attr', 123)")).isEqualTo(StarlarkInt.of(123));
    assertThat(getSaved("'define_values' in r")).isEqualTo(true);
    assertThat(getSaved("'invalid_attr' in r")).isEqualTo(false);
  }

  @Test
  public void existingRules_returnsObjectWithCorrectMutability() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('inc.bzl', 'f')", //
        "f()");
    scratch.file(
        "test/inc.bzl", //
        "def f():",
        "  native.config_setting(name='x', define_values={'key': 'value'})",
        "  rs = native.existing_rules()",
        "  rs['no_such_rule'] = {'name': 'no_such_rule', 'kind': 'config_setting'}"); // mutate

    assertThat(getConfiguredTarget("//test:BUILD")).isNotNull(); // no error on mutation
  }

  @Test
  public void existingRules_returnsDictLikeObject() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('inc.bzl', 'f')", //
        "f()");
    scratch.file(
        "test/inc.bzl", //
        "def f():",
        "  native.config_setting(name='x', define_values={'key_x': 'value_x'})",
        "  native.config_setting(name='y', define_values={'key_y': 'value_y'})",
        "  rs = native.existing_rules()",
        "  print('rs == %s' % repr(rs))",
        "  test.save('[key for key in rs]', [key for key in rs])",
        "  test.save('list(rs)', list(rs))",
        "  test.save('rs.keys()', rs.keys())",
        "  test.save(\"[v['name'] for v in rs.values()]\", [v['name'] for v in rs.values()])",
        "  test.save(\"[(i[0], i[1]['name']) for i in rs.items()]\", [(i[0], i[1]['name']) for i in"
            + " rs.items()])",
        "  test.save(\"rs['x']['define_values']\", rs['x']['define_values'])",
        "  test.save(\"rs.get('x', {'name': 'z'})['name']\", rs.get('x', {'name': 'z'})['name'])",
        "  test.save(\"rs.get('invalid_rule', {'name': 'invalid_rule'})\", rs.get('invalid_rule',"
            + " {'name': 'invalid_rule'}))",
        "  test.save(\"'x' in rs\", 'x' in rs)",
        "  test.save(\"'invalid_rule' in rs\", 'invalid_rule' in rs)");

    assertThat(getConfiguredTarget("//test:BUILD")).isNotNull(); // no error
    assertThat(Starlark.toIterable(getSaved("[key for key in rs]"))).containsExactly("x", "y");
    assertThat(Starlark.toIterable(getSaved("list(rs)"))).containsExactly("x", "y");
    assertThat(Starlark.toIterable(getSaved("rs.keys()"))).containsExactly("x", "y");
    assertThat(Starlark.toIterable(getSaved("[v['name'] for v in rs.values()]")))
        .containsExactly("x", "y");
    assertThat(Starlark.toIterable(getSaved("[(i[0], i[1]['name']) for i in rs.items()]")))
        .containsExactly(Tuple.of("x", "x"), Tuple.of("y", "y"));
    assertThat(getSaved("rs['x']['define_values']"))
        .isEqualTo(Dict.builder().put("key_x", "value_x").buildImmutable());
    assertThat(getSaved("rs.get('x', {'name': 'z'})['name']")).isEqualTo("x");
    assertThat(getSaved("rs.get('invalid_rule', {'name': 'invalid_rule'})"))
        .isEqualTo(Dict.builder().put("name", "invalid_rule").buildImmutable());
    assertThat(getSaved("'x' in rs")).isEqualTo(true);
    assertThat(getSaved("'invalid_rule' in rs")).isEqualTo(false);
  }

  @Test
  public void existingRules_returnsSnapshotOfOnlyRulesInstantiatedUpToThatPoint() throws Exception {
    scratch.file(
        "test/BUILD",
        "load('inc.bzl', 'f')", //
        "f()");
    scratch.file(
        "test/inc.bzl", //
        "def f():",
        "  native.config_setting(name='x', define_values={'key_x': 'value_x'})",
        "  rs1 = native.existing_rules()",
        "  native.config_setting(name='y', define_values={'key_y': 'value_y'})",
        "  rs2 = native.existing_rules()",
        "  native.config_setting(name='z', define_values={'key_z': 'value_z'})",
        "  rs3 = native.existing_rules()",
        "  test.save('rs1.keys()', rs1.keys())",
        "  test.save('rs2.keys()', rs2.keys())",
        "  test.save('rs3.keys()', rs3.keys())");

    assertThat(getConfiguredTarget("//test:BUILD")).isNotNull(); // no error
    assertThat(Starlark.toIterable(getSaved("rs1.keys()"))).containsExactly("x");
    assertThat(Starlark.toIterable(getSaved("rs2.keys()"))).containsExactly("x", "y");
    assertThat(Starlark.toIterable(getSaved("rs3.keys()"))).containsExactly("x", "y", "z");
  }

  /**
   * Tests for {@code native.existing_rule} and {@code native.existing_rules} Starlark functions
   * with the {@code --experimental_existing_rules_immutable_view} flag set.
   */
  @RunWith(JUnit4.class)
  public static final class WithImmutableView extends NativeExistingRulesTest {

    @Override
    protected void setupOptions() throws Exception {
      setBuildLanguageOptions("--experimental_existing_rules_immutable_view");
    }

    @Test
    @Override
    public void existingRule_returnsObjectWithCorrectMutability() throws Exception {
      scratch.file(
          "test/BUILD",
          "load('inc.bzl', 'f')", //
          "f()");
      scratch.file(
          "test/inc.bzl", //
          "def f():",
          "  native.config_setting(name='x', define_values={'key': 'value'})",
          "  r = native.existing_rule('x')",
          "  r['no_such_attribute'] = 123"); // mutate the view

      reporter.removeHandler(failFastHandler);
      assertThat(getConfiguredTarget("//test:BUILD")).isNull(); // mutation fails
      assertContainsEvent("can only assign an element in a dictionary or a list");
    }

    @Test
    @Override
    public void existingRules_returnsObjectWithCorrectMutability() throws Exception {
      scratch.file(
          "test/BUILD",
          "load('inc.bzl', 'f')", //
          "f()");
      scratch.file(
          "test/inc.bzl", //
          "def f():",
          "  native.config_setting(name='x', define_values={'key': 'value'})",
          "  rs = native.existing_rules()",
          "  rs['no_such_rule'] = {'name': 'no_such_rule', 'kind': 'config_setting'}"); // mutate

      reporter.removeHandler(failFastHandler);
      assertThat(getConfiguredTarget("//test:BUILD")).isNull(); // mutation fails
      assertContainsEvent("can only assign an element in a dictionary or a list");
    }

    @Test
    public void existingRules_returnsDeeplyImmutableView() throws Exception {
      scratch.file(
          "test/BUILD",
          "load('inc.bzl', 'f')", //
          "f()");
      scratch.file(
          "test/inc.bzl", //
          "def f():",
          "  native.config_setting(name='x', define_values={'key': 'value'})",
          "  rs = native.existing_rules()",
          "  rs['x']['define_values']['key'] = 123"); // mutate an attribute value within the view

      reporter.removeHandler(failFastHandler);
      assertThat(getConfiguredTarget("//test:BUILD")).isNull();
      assertContainsEvent("trying to mutate a frozen dict value");
    }
  }
}
