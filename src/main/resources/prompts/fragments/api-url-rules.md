API URL RULE — MANDATORY:
Frontend code calling the backend API must ALWAYS use RELATIVE URLs.
GOOD: fetch('/api/products')  |  axios.create({ baseURL: '/api' })
BAD:  fetch('http://localhost:8080/api/products')
The app runs behind a dev-server proxy. Absolute localhost URLs bypass the proxy and break in Docker preview.
