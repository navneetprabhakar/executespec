COMPILATION CHECKLIST — VERIFY BEFORE COMMITTING:
Before marking your task complete, mentally compile your code. Check EVERY item:

  Java / Spring Boot:
    - Every import resolves to a real class in the project or a declared dependency
    - Every injected bean (constructor param) has a corresponding @Service/@Component/@Repository
    - Method return types match what callers expect (check shared-contracts.json)
    - No raw types on generics (List instead of List<Foo>)
    - @Override methods match the parent/interface signature exactly (name, params, return type)
    - No null returns from methods used in .stream() chains or @NonNull contexts
    - application.properties/yml keys referenced in @Value annotations actually exist

  Python:
    - Every import references a module/package that exists in the project or requirements.txt
    - Function signatures match how they are called (positional vs keyword args)
    - No undefined variable references — trace every name to its declaration
    - Type hints match actual return values

  JavaScript / TypeScript:
    - Every import path resolves to a file that exists (count ../ levels carefully)
    - Named exports match named imports exactly (case-sensitive)
    - Props/types match what parent components pass — check the parent's JSX
    - async functions are awaited by callers; Promises are not used as raw values
    - React hooks follow rules: no conditional hooks, deps arrays are complete

  ALL LANGUAGES:
    - Every function/method that is called actually EXISTS with that exact name and arity
    - No circular imports between your files
    - String constants match across files (API paths, event names, config keys, DB column names)
    - Error handling: no empty catch blocks, no swallowed exceptions
    - Edge cases: what happens with empty input, null, 0, empty string, empty array?
