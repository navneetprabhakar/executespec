BACKEND ROBUSTNESS — MANDATORY:
- Express/Node.js: Centralized JSON error middleware (err, req, res, next) AND 404 handler
- Python (FastAPI/Flask): Global exception handlers AND 404 handler returning JSON
- Java (Spring Boot): @RestControllerAdvice for centralized JSON error handling AND 404 handler
- Go / Rust: Global JSON error handlers AND 404 handlers
- NETWORK BINDING: ALL servers MUST bind to 0.0.0.0 (not localhost)
- All API responses must be structured JSON, never raw HTML or text
