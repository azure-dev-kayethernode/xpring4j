package io.xpring.payid;

import static okhttp3.CookieJar.NO_COOKIES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

/**
 * Implementation of {@link PayIDResolver} which resolves {@link PayID} to URLs using
 * automated mode.
 *
 * @see "https://github.com/xpring-eng/rfcs/blob/master/payid/src/spec/payid-discovery.md#automated-mode"
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class AutoModePayIDResolver implements PayIDResolver {

  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  protected static final String DISCOVERY_URL = "http://payid.org/rel/discovery/1.0";
  protected static final String PAY_ID_URL = "http://payid.org/rel/payid/1.0";
  protected static final String WEBFINGER_URL = ".well-known/webfinger";
  private OkHttpClient okHttpClient;
  private ObjectMapper objectMapper;

  /**
   * No-args constructor.
   */
  public AutoModePayIDResolver() {
    this(newOkHttpClient(), new ObjectMapper());
  }

  /**
   * Required args constructor.  Initializes with a default {@link ObjectMapper}.
   *
   * @param okHttpClient An {@link OkHttpClient} to be used by this resolver.
   */
  public AutoModePayIDResolver(OkHttpClient okHttpClient) {
    this(okHttpClient, new ObjectMapper());
  }

  /**
   * Required args constructor.
   *
   * @param okHttpClient An {@link OkHttpClient} to be used by this resolver.
   * @param objectMapper An {@link ObjectMapper} to be used by this resolver.
   */
  public AutoModePayIDResolver(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
    this.okHttpClient = okHttpClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Resolves a {@link PayID} to an {@link HttpUrl} using automated mode.
   *
   * <p>
   *   This implementation recursively sends WebFinger GET requests to the PayID host
   *   until a non-WebFinger URL is returns as the href of the WebFinger Link.  This means that
   *   WebFinger servers can delegate PayID resolution to different hosts.
   * </p>
   *
   * @param payID A {@link PayID} to resolve to an {@link HttpUrl}.
   * @return The {@link Optional} of {@link HttpUrl} of the PayID server for a given PayID, or {@link Optional#empty()}
   *          if there is no WebFinger server running at the PayID's host URL.
   */
  @Override
  public HttpUrl resolvePayIDUrl(PayID payID) {
    WebFingerLink webFingerLink = this.getWebFingerPayIDLink(payID);

    // Recurse through webfinger href responses until either the webfinger redirect doesn't exist or until
    // we get a non webfinger href URL, in which case we can infer that the href is a PayID server URL.
    while (webFingerLink.rel().equals(DISCOVERY_URL)) {
      HttpUrl nextWebfingerUrl = expandUrlTemplate(webFingerLink, payID);
      webFingerLink = this.getWebFingerPayIDLink(nextWebfingerUrl);
    }

    // On the last webfinger call, the webfinger href was invalid or doesnt exist, in which case we should fall back
    // to manual mode resolution.
    return expandUrlTemplate(webFingerLink, payID);
  }

  /**
   * Expands a {@link WebFingerLink#template()} if no href exists, otherwise returns the href.
   *
   * @param webFingerLink A {@link WebFingerLink} with either an href or template.
   * @param payID A {@link PayID} which should be used to expand the template.
   * @return The href if it exists, or the expanded template.
   */
  protected HttpUrl expandUrlTemplate(WebFingerLink webFingerLink, PayID payID) {
    String expandedUrl = webFingerLink.href()
      .orElseGet(() -> {
        String template = webFingerLink.template()
          .orElseThrow(() -> new PayIDDiscoveryException(PayIDDiscoveryExceptionType.UNKNOWN, ""));
        UriTemplate uriTemplate = new UriTemplate(template);
        URI expandedTemplate = uriTemplate.expand(payID.account());
        return expandedTemplate.toString();
      });

    return HttpUrl.parse(expandedUrl);
  }

  /**
   * Get a WebFinger Link for a given PayID.
   *
   * @param payID The {@link PayID} whose host should be queried for a WebFinger Link
   * @return A present {@link WebFingerLink} containing a resolved URL or {@link Optional#empty()} if the WebFinger
   *          server was unreachable or did not provide an appropriate link.
   * @throws JsonProcessingException if the WebFinger response could not be parsed.
   */
  protected WebFingerLink getWebFingerPayIDLink(PayID payID) {
    HttpUrl webfingerUrl = new HttpUrl.Builder()
        .scheme("https")
        .host(payID.host())
        .addEncodedPathSegments(WEBFINGER_URL)
        .addQueryParameter("resource", payID.toString())
        .build();

    return this.getWebFingerPayIDLink(webfingerUrl);
  }

  /**
   * Get a WebFinger Link from a given URL.
   *
   * @param webfingerUrl The {@link HttpUrl} of a WebFinger server endpoint.
   * @return A present {@link WebFingerLink} containing a resolved URL or {@link Optional#empty()} if the WebFinger
   *          server was unreachable or did not provide an appropriate link.
   * @throws JsonProcessingException if the WebFinger response could not be parsed.
   */
  protected WebFingerLink getWebFingerPayIDLink(HttpUrl webfingerUrl) {
    try {
      String jrdString = this.executeForJrdString(webfingerUrl);
      WebFingerJrd typedJrd = objectMapper.readValue(jrdString, WebFingerJrd.class);

      return typedJrd.links().stream()
        .filter(link -> link.rel().equals(DISCOVERY_URL) || link.rel().equals(PAY_ID_URL))
        .findFirst()
        .orElseThrow(() -> null);  // TODO: throw somethin
    } catch (JsonProcessingException e) {
      logger.warn("Unable to deserialize WebFinger JRD! message: {}", e.getMessage());
      throw new PayIDDiscoveryException(PayIDDiscoveryExceptionType.UNKNOWN, "Unable to deserialize WebFinger JRD!");
    }
  }

  /**
   * Execute a GET request on the given WebFinger URL for a JRD.
   *
   * <p>
   *   This is split off as a separate method to increase testability/mocking of this class.
   * </p>
   *
   * @param webfingerUrl the {@link HttpUrl} of the WebFinger server endpoint.
   * @return A present {@link String} containing the JSON payload of the request response, or {@link Optional#empty()}
   *          if the request failed.
   */
  protected String executeForJrdString(HttpUrl webfingerUrl) {
    Request webfingerRequest = new Request.Builder()
        .header(HttpHeaders.CONTENT_TYPE, "application/json")
        .header(HttpHeaders.ACCEPT, "application/json")
        .url(webfingerUrl)
        .get()
        .build();

    try (Response response = this.okHttpClient.newCall(webfingerRequest).execute()) {
      // Auto mode not enabled
      if (response.code() >= 400 && response.code() <= 500) {
        throw new PayIDDiscoveryException(PayIDDiscoveryExceptionType.UNKNOWN, "WebFinger server returned an exception.");
      }

      ResponseBody body = response.body();
      if (body == null) {
        throw new PayIDDiscoveryException(PayIDDiscoveryExceptionType.UNKNOWN, "WebFinger server didn't return a JRD.");
      }

      return body.string();
    } catch (IOException e) {
      logger.warn("Failed to execute WebFingerRequest. message: {}", e.getMessage());
      throw new PayIDDiscoveryException(PayIDDiscoveryExceptionType.UNKNOWN, "Failed to execute WebFinger request.");
    }
  }

  /**
   * Constructs a new {@link OkHttpClient}.
   *
   * @return A new {@link OkHttpClient}.
   */
  private static OkHttpClient newOkHttpClient() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).build();
    builder.connectionSpecs(Arrays.asList(spec, ConnectionSpec.CLEARTEXT));
    builder.cookieJar(NO_COOKIES);

    return builder.build();
  }
}
