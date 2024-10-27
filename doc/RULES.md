# Coding rules

Keeping the below in mind will make code reviews faster and lead to
fewer comments. It also makes the code cleaner, leads to fewer bugs and
makes code easier to maintain.

Some of these rules are opinionated (meaning there could also be good
reasons for another choice). The authors can decide to change these rules
so bring it up if you disagree or don't see the rationale.

## Language & Frameworks

- Language: Java & English (so no Dutch or Dunglish)
- JVM: preferably latest but at least 17
- Spring-boot (for now)
- JUnit 5
- High test coverage (test functionality, not functions)
- Use Lombok where possible (prevents repetitive & duplicate code).
  Don't use Lombok 'experimental' features.
- Use logging (@Slf4j)
- Use WebClient (Reactive) instead of RestTemplate
- Prefer functionality in 3rd-party libraries over self-cooked.
  There are sometimes good reasons to skip this rule but make
  sure that you have a very good explanation for it.

## Code: high level

- Limit state as much as possible. State increases complexity, creates
  multithreading issues and adds test effort.
  It is unfortunate Java did not implement compile time immutable
  collection support because they thought it bloated the API too much:
  https://docs.oracle.com/javase/1.5.0/docs/guide/collections/designfaq.html#1
- Singletons (final classes with a private empty default constructor)
  are not allowed to have state. Singletons should only be used for
  generic utility functionality. This because state needs testing and
  singletons cannot be mocked.
- All variables are @NonNullByDefault (via package-info.java).
  (javax) @Nullable can be used as an exception, but Optional is
  preferred. This way there is no need for null-checks (unless Nullable).
- Use dependency injection where possible (via @AllArgsConstructor, don't
  use @Autowired). This keeps stronger separation between components
  and simplifies testing.
- Try to limit SpringBoot tests. These tests are slow. We like to have
  as many tests as possible. Slow tests make the test suite as a whole
  take longer which puts on pressure to limit testing.
- Spring Boot controllers (rest handlers) should contain as little code
  as possible. Just call the associated service immediately. This way
  Spring Boot tests are not necessary and only the service can be tested
  directly which is much faster.
- Use Mono when possible. They run code asynchronously which
  improves performance and lowers thread count. For example Spring
  supports several backends, two of those who are Tomcat (sync) and
  Netty (async). Handling requests using Netty with Mono compared
  to MVC Tomcat improves performance by about 30% and have lower
  thread and memory use. Mono & Flux take
  some getting used to but their functional logic leads to clearer code.
- Unchecked exceptions everywhere. When a call is made that has checked
  exceptions, use noThrow(...) or noCheckedThrow(...). Checked exceptions
  are an abstraction leak and make the black box transparent. They may
  require changes to the calling code when an internal change is made.
  Also throwing checked exceptions is not possible in lambdas by default.
- Try to keep logic out of constructors. Use factory methods instead.
  It is best to not code a constructor at all but just use @RequiredArgsConstructor.

## Code: low level

- Use spaces, not tabs. Tabs look different for everybody, even if potentially.
- Small functions with low cognitive complexity (nice to have: some metrics plugin?)
- Self documenting code (descriptive method, field & variable names).
  Be minimal with remarks.
- Prevent loops as much as possible. Use streams, filters, map, reduce, etc.
- Prevent ifs as much as possible. Use filters instead.
- Constants should be static final and have naming in CAPITALS.
- Constants should be kept in the class that uses them unless they are
  used by multiple classes in which case they should be put in a 
  separate singleton class named <Name>Constants.
- It is ok to align blocks to improve readability (explicitly added here
  because in some organisations this is forbidden). To prevent the auto
  formatter from mangling these lines, add a //@formatter:off|on before
  and after the block to protect. This needs to be enabled in IntelliJ:
  settings -> editor -> code style -> formatter -> "Turn formatter on/off
  with markers in code comments".
- Don't use @ComponentScan() with strings but use basePackageClasses instead.
  Marker interfaces are defined for each package that can be scanned.
  This keeps working even if package names are refactored, unlike strings.
- 