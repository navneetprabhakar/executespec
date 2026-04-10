ROUTER NAMESPACE ISOLATION:
If a router is mounted at a path (e.g., app.use('/api', router) in app.js), the internal routes MUST NOT repeat the path.
GOOD: router.get('/movies') → results in /api/movies
BAD:  router.get('/api/movies') → results in /api/api/movies (BREAKS FRONTEND)
