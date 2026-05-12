package com.navneet.executespec.util;

import java.io.InputStream;
import java.io.Reader;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Defense-in-depth SnakeYAML loader.
 *
 * <p>{@code new Yaml().load(...)} uses the default {@link
 * org.yaml.snakeyaml.constructor.Constructor} which deserialises {@code
 * !!javax.script.ScriptEngineManager} and similar gadgets — CVE-2022-1471 class. Even when input is
 * classpath-only today, a malicious YAML landing in classpath (e.g. via a community-contributed
 * readymade or migration recipe before W7 moderation) would execute code.
 *
 * <p>All catalog/recipe loaders in this codebase MUST go through this helper instead of
 * constructing {@code new Yaml()} directly. {@link SafeConstructor} restricts deserialisation to
 * scalars, maps, lists, and core schema types.
 *
 * <p>Audit: 2026-05-12 line-by-line review item D 27-30.
 */
public final class SafeYamlLoader {

  private SafeYamlLoader() {}

  /**
   * Build a Yaml instance configured for safe loading. Reusable; thread-safe per SnakeYAML docs.
   */
  public static Yaml safe() {
    LoaderOptions opts = new LoaderOptions();
    // Conservative caps — readymades + migration recipes are small.
    opts.setMaxAliasesForCollections(50);
    opts.setAllowDuplicateKeys(false);
    return new Yaml(new SafeConstructor(opts));
  }

  /** Load a YAML document from a string. Returns {@code null} on empty/blank input. */
  public static Object load(String yamlBody) {
    if (yamlBody == null || yamlBody.isBlank()) return null;
    return safe().load(yamlBody);
  }

  /** Load a YAML document from an InputStream. */
  public static Object load(InputStream in) {
    return safe().load(in);
  }

  /** Load a YAML document from a Reader. */
  public static Object load(Reader r) {
    return safe().load(r);
  }
}
