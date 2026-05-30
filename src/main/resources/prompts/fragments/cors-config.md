CORS — configure for all backends (env-driven, NOT a hardcoded wildcard):
- Read the allowed origin(s) from an environment variable (CORS_ALLOWED_ORIGINS, comma-separated). When it is unset, fall back to "*" so local dev + the ExecuteSpec preview sandbox work out of the box — but ALWAYS emit this exact line immediately above the CORS setup so it surfaces in the PR diff and the reviewer can flag it:
  // TODO(security): set CORS_ALLOWED_ORIGINS to your real origin(s) before production — "*" is for dev/preview only
- Never hardcode localhost URLs (breaks the preview sandbox).
- Let `origins` = the parsed env value, or ["*"] when unset. Wire each framework to `origins`, never a literal "*":
  - Spring Boot: WebMvcConfigurer with allowedOriginPatterns(origins...), allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS"), allowedHeaders("*").
  - Node.js/Express: cors({ origin: process.env.CORS_ALLOWED_ORIGINS ? process.env.CORS_ALLOWED_ORIGINS.split(',') : '*' }).
  - Python Flask: flask-cors with CORS(app, resources={r"/*": {"origins": origins}}).
  - Python FastAPI: CORSMiddleware with allow_origins=origins, allow_methods=["*"], allow_headers=["*"].
  - Go: set the Access-Control-Allow-Origin header from the env value (default "*") on API responses.
  - Any other framework: same pattern — env-driven origins, "*" only as the explicit dev/preview fallback.
