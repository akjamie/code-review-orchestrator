# Security Checkpoints

Load this file during **Step 5 (Cross-cutting concerns)** when the change touches auth, input parsing, external APIs, crypto, or any data handling.

---

## Input validation

- [ ] All external input (HTTP params, JSON bodies, file contents, environment variables) is validated before use.
- [ ] Validate type, length, range, and format. Reject early, fail closed.
- [ ] Use bean validation (`@NotNull`, `@Size`, `@Pattern`, etc.) for API input, but don't rely on it alone for security-critical paths.
- [ ] Sanitize input before logging to prevent log injection (newlines, control characters).

## Injection prevention

- [ ] **SQL**: Use parameterized queries / prepared statements. Never string-concatenate SQL. Verify ORM queries use bind parameters, not string interpolation.
- [ ] **Command injection**: Use `ProcessBuilder` with argument lists. Never `Runtime.exec("cmd " + userInput)`.
- [ ] **LDAP/XPath/Expression injection**: Use parameterized APIs, never string concatenation.
- [ ] **XSS**: Escape output when rendering user content in HTML. Use framework escaping, not manual replacement.
- [ ] **Path traversal**: Validate file paths against a base directory (`Path.normalize()`, `Path.startsWith(baseDir)`). Never trust user-supplied filenames.

## Authentication & authorization

- [ ] Authentication checks happen before any business logic executes.
- [ ] Authorization checks are on every endpoint that needs protection, not just some.
- [ ] Role checks use the principle of least privilege.
- [ ] Passwords are hashed with bcrypt/argon2, never MD5/SHA1. Never stored in plain text.
- [ ] Session tokens are generated with a cryptographically secure random generator (`SecureRandom`), not `Math.random()` or `Random`.

## Cryptography

- [ ] Use standard, vetted algorithms (AES-GCM, RSA-2048+, ECDSA). Never custom crypto.
- [ ] Use `javax.crypto` / `java.security` APIs correctly. Never hardcode IVs or keys.
- [ ] Use constant-time comparison for security-sensitive equality checks (`MessageDigest.isEqual()`), never `String.equals()` for tokens/signatures.
- [ ] Keys are stored in a key vault / environment, never in source code or config files.

## Secret management

- [ ] No hardcoded secrets, API keys, passwords, or tokens in source code.
- [ ] Secrets come from environment variables, vault services, or Spring Cloud Config - never from committed properties files.
- [ ] Secrets are not logged. Verify log statements and exception messages don't include tokens.
- [ ] `.gitignore` excludes secret files. No secrets in git history (check with `git log -p`).

## Deserialization

- [ ] Don't use `ObjectInputStream` on untrusted data. Use JSON/XML with type-restricted deserialization.
- [ ] Jackson: disable `default typing` or use `@JsonTypeInfo` with allowed subtypes. Polymorphic deserialization from untrusted input is an RCE vector.
- [ ] XML parsing: disable external entity processing (`XXE`) - set `FEATURE_SECURE_PROCESSING`, disable DTDs.

## Dependencies

- [ ] No known vulnerable dependencies. Check with `dependencyCheck` or Dependabot.
- [ ] No deprecated or end-of-life libraries.
- [ ] Minimal dependency surface. Each dependency is justified.
