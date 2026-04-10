CORS — MANDATORY for all backends:
- Spring Boot: WebMvcConfigurer with allowedOriginPatterns("*"), allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS"), allowedHeaders("*"). Do NOT use allowedOrigins with specific URLs.
- Node.js/Express: cors({ origin: '*' }) middleware
- Python Flask: flask-cors with CORS(app, resources={r"/*": {"origins": "*"}})
- Python FastAPI: CORSMiddleware with allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
- Go: Access-Control-Allow-Origin: * header on all API responses
- Any other framework: allow ALL origins — never hardcode localhost URLs
