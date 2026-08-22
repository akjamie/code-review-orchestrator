# Architecture & SOLID Checkpoints

Load this file during **Step 2 (Architectural pass)** and **Step 3 (Design pass)** of the review workflow.

---

## Clean Architecture

### Layer separation

Verify the code respects a clear layering. A typical Java application has:

```
Presentation (Controllers / REST endpoints)
        │  depends on ▼
Application / Service (Use cases, orchestration)
        │  depends on ▼
Domain (Entities, value objects, domain services - pure business logic)
        │  depends on ▼
Infrastructure (DB, external APIs, file I/O, framework code)
```

- [ ] **Dependency rule**: dependencies point inward. Domain has zero framework imports (no JPA, no Spring, no Jackson annotations on domain entities). Infrastructure depends on domain, never the reverse.
- [ ] Presentation layer contains no business logic. Controllers validate input, delegate to services, and format output. That's it.
- [ ] Service layer contains use-case orchestration, not domain rules. Domain rules live in domain objects.
- [ ] Infrastructure details (SQL, HTTP clients, file paths) are not leaked into service or domain layers. Hide them behind interfaces.

### Module boundaries

- [ ] Modules communicate through well-defined interfaces, not through shared mutable state.
- [ ] No circular dependencies between packages or modules. If A depends on B, B must not depend on A.
- [ ] Cross-module calls go through interfaces, not concrete classes. This enables testing and replacement.
- [ ] A change in one module does not cascade into changes across many modules. High cohesion, low coupling.

### Dependency inversion

- [ ] High-level policy depends on abstractions (interfaces), not concrete implementations.
- [ ] Interfaces are owned by the high-level module (client), not the low-level module (implementation). The implementation depends on the interface, not the other way around.
- [ ] Dependency injection is used to wire implementations to interfaces. No `new` for service dependencies inside business logic - inject them.

### Entity and value object design

- [ ] Value objects are immutable. Two value objects with the same fields are equal.
- [ ] Entities have a clear identity (ID) and their lifecycle is managed explicitly.
- [ ] Domain objects enforce their own invariants. A `Money` class should not allow negative amounts if that's a business rule. Don't rely on external validation alone.
- [ ] No anemic domain models - domain objects should have behavior, not just getters and setters. If your domain model is just a bag of fields with accessors, it's a DTO, not a domain model.

---

## SOLID

- [ ] **S - Single Responsibility**: Each class has one reason to change. A class that handles parsing, validation, and persistence violates SRP.
- [ ] **O - Open/Closed**: New behavior is added through new classes / implementations, not by modifying existing code. If adding a new payment type requires editing the `PaymentProcessor` switch statement, it violates OCP.
- [ ] **L - Liskov Substitution**: Subtypes are substitutable for their base types without breaking behavior. A subclass that throws `UnsupportedOperationException` for an inherited method violates LSP.
- [ ] **I - Interface Segregation**: Clients are not forced to depend on methods they don't use. Fat interfaces should be split into role-specific interfaces.
- [ ] **D - Dependency Inversion**: High-level modules don't depend on low-level modules; both depend on abstractions. No `new ConcreteService()` inside a business-logic class.
