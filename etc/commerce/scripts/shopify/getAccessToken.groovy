import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// Obtain Shopify access token via Client Credentials Grant and cache it in JCR.
// If a cached token exists, it is reused without making an API call.

def accessTokenNode = repositorySession.getResource("/etc/commerce/config/access_token")

// Check for cached token
if (accessTokenNode.exists()) {
    if (accessTokenNode.getLastModified().getTime() + 23 * 60 * 60 * 1000 < System.currentTimeMillis()) {
        log.info("Cached Shopify access token is expired, will obtain a new one")
    } else {
        log.info("Cached Shopify access token is still valid, will reuse it")
        def cached = accessTokenNode.content
        if (cached != null && !cached.toString().trim().isEmpty()) {
            context.setAttribute("shopifyAccessToken", cached.toString().trim())
            log.info("Using cached Shopify access token from JCR")
            return
        }
    }
}

// No cached token — obtain via Client Credentials Grant
def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
def config = YAML.parse(configNode)

// Admin API connection settings live under the `adminApi` group. Fall back to
// the top level so a legacy flat shopify.yml keeps working.
def adminApi = config.adminApi ?: config

def clientID = adminApi.clientID
def clientSecret = adminApi.clientSecret
def shopDomain = adminApi.shopDomain

def tokenRequestBody = JSON.stringify([
    client_id: clientID,
    client_secret: clientSecret,
    grant_type: "client_credentials"
])

def httpClient = HttpClient.newHttpClient()
def tokenRequest = HttpRequest.newBuilder()
    .uri(URI.create("https://${shopDomain}/admin/oauth/access_token"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(tokenRequestBody))
    .build()

def tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString())

if (tokenResponse.statusCode() != 200) {
    throw new RuntimeException(
        "Failed to obtain Shopify access token: ${tokenResponse.statusCode()} - ${tokenResponse.body()}"
    )
}

def responseJson = JSON.parse(tokenResponse.body())
def accessToken = responseJson.access_token

if (accessToken == null || accessToken.toString().trim().isEmpty()) {
    throw new RuntimeException("Shopify returned empty access token: ${tokenResponse.body()}")
}

// Cache token in JCR
try {
    if (accessTokenNode.exists()) {
        accessTokenNode.write(accessToken)
        accessTokenNode.setProperty("jcr:lastModified", new Date())
    } else {
        accessTokenNode.createFile().write(accessToken)
    }
    repositorySession.commit()
    log.info("Shopify access token obtained and cached in JCR")
} catch (Exception e) {
    repositorySession.rollback()
    throw new RuntimeException("Failed to cache access token in JCR: ${e.message}", e)
}

context.setAttribute("shopifyAccessToken", accessToken.toString().trim())
