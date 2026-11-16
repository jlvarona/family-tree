// FT-005: local development server for :app:webApp (JS and Wasm).
// Kotlin appends every file in webpack.config.d to the generated webpack config.
//
// - Serves the app on http://localhost:3000, a redirect URI the family-tree realm
//   already allows for the family-tree-web client (ops/keycloak/realm-export.json).
//   Kotlin's default dev port, 8080, is the Ktor server's.
// - Proxies /api to the Ktor server on http://localhost:8080, so the browser calls
//   the API on its own origin and the server needs no CORS. Production must route
//   /api to the server on the web app's origin in the same way (README).
if (config.devServer) {
    config.devServer.port = 3000;
    config.devServer.proxy = [
        {
            context: ["/api"],
            target: "http://localhost:8080",
            changeOrigin: false,
        },
    ];
}
