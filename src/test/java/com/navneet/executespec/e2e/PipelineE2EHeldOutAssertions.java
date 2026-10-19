package com.navneet.executespec.e2e;

/** Behavior assertions run independently of the generated candidate's own test suite. */
final class PipelineE2EHeldOutAssertions {
  private PipelineE2EHeldOutAssertions() {}

  static String slugify() {
    return """
           import main
           cases = {
               '  Hello, WORLD!! ': 'hello-world', '---': '', 'a___b': 'a-b',
               '': '', 'A123 B': 'a123-b', 'café': 'caf', '東京': '',
               'x...y': 'x-y', ' a--b__c ': 'a-b-c', 'A-B-C': 'a-b-c',
               'İTEM': 'i-tem', '１２３': '', 'éclair': 'e-clair', 'A🔥B': 'a-b'
           }
           for value, expected in cases.items():
               actual = main.slugify(value)
               assert actual == expected, (value, expected, actual)
           print('held-out 14/14 passed')
           """;
  }
}
