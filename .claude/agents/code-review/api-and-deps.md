# API Design & Dependency Checkpoints

Load this file during **Step 5 (Cross-cutting concerns)** when the change touches REST endpoints, public APIs, or build configuration.

---

## API Design

### REST endpoints

- [ ] HTTP methods are correct: GET (idempotent, no side effects), POST (create), PUT (full update, idempotent), PATCH (partial update), DELETE.
- [ ] Status codes are correct: 200 (success), 201 (created), 204 (no content), 400 (bad request), 401 (unauthorized), 403 (forbidden), 404 (not found), 409 (conflict), 422 (validation error), 500 (server error). Don't return 200 with an error body.
- [ ] Endpoints are versioned (`/api/v1/...`) if backward compatibility matters.
- [ ] Input validation returns 400 with clear field-level error messages.
- [ ] No sensitive data in URL paths or query parameters (use headers / body). URLs are logged by proxies.

### General API design

- [ ] Public APIs are documented (Javadoc, OpenAPI/Swagger). Documentation is kept in sync with the code.
- [ ] APIs are consistent with the rest of the codebase. If other endpoints use `userId`, don't introduce `uid`.
- [ ] Request/response DTOs are stable and don't leak internal domain structures directly. Map between domain and DTO explicitly.
- [ ] No breaking changes to public APIs without versioning or migration path.

---

## Dependency & Build

- [ ] Dependencies are justified. Each `implementation(...)` in the build file should have a clear purpose.
- [ ] Dependency versions are managed centrally (BOM, version catalog, or platform) - no scattered hardcoded versions.
- [ ] Test dependencies have `testImplementation` scope, not `implementation`.
- [ ] No `*` wildcards or `LATEST` versions. Pin specific versions.
- [ ] No duplicate dependencies with conflicting versions (check transitive dependencies).
- [ ] The build is reproducible - `./gradlew clean build` works on a fresh checkout.
