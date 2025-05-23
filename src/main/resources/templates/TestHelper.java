package sa.com.cloudsolutions.antikythera.base;

import sa.com.cloudsolutions.antikythera.configurations.Configurations;
import sa.com.cloudsolutions.antikythera.constants.StaticHeaders;
import com.cloud.api.rest.APIRequester;
import com.cloud.core.config.enums.ConfigKeys;
import com.cloud.core.reporting.ExtentLogger;
import com.cloud.core.testdataprovider.enums.DataProviderType;
import com.cloud.core.testdataprovider.utils.DataProviderUtil;
import com.csi.support.api.dataService.TokenDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.http.Method;
import io.restassured.response.Response;
import org.json.JSONObject;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.asserts.SoftAssert;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.cloud.api.base.APIBaseTest;
import com.cloud.api.base.APIBaseService;
import com.cloud.api.base.APIBaseClass;

public abstract class TestHelper extends APIBaseTest {

    private String baseURI;
    public static HashMap<String, String> loginProperties = null;
    public static HashMap<String, String> urlProperties = null;
    public static Header authorizationHeader = null;

    protected static SoftAssert softAssert;
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    APIBaseService apiBaseService = new APIBaseService();

    @BeforeSuite
    public void loadEnvironmentData() throws Exception {
        String path = config.getValue(ConfigKeys.KEY_DATA_FILE_PATH.getKey()) + config.getValue(ConfigKeys.KEY_ENVIRONMENT.getKey()).toLowerCase();
        DataProviderUtil.setDataFile(path, DataProviderType.PROPERTY);
        DataProviderUtil.loadData(EnumSet.allOf(Configurations.class).stream().map(Configurations::name).collect(Collectors.toList()));
        loginProperties = DataProviderUtil.getDataSet("Data", Configurations.Login.name());
        urlProperties = DataProviderUtil.getDataSet("Data", Configurations.Url.name());
        TokenDataProvider.setTokenDataService(urlProperties.get("tokenServices.host"));
        authorizationHeader = new Header("authorization", TokenDataProvider.getInstance().tokenGenerate(loginProperties.get("username"), loginProperties.get("password")));
    }

    @BeforeClass
    public void serviceSetUp()  {
        baseURI = urlProperties.get("application.host") + urlProperties.get("application.version");
        APIBaseClass.headers = new Headers(
                StaticHeaders.CONTENT_TYPE_JSON,
                StaticHeaders.X_GROUP,
                StaticHeaders.X_HOSPITAL,
                StaticHeaders.X_USER,
                StaticHeaders.X_LOCATION,
                StaticHeaders.ACCEPT_ALL,
                authorizationHeader
        );
    }

    @BeforeMethod
    public void beforeMethod() throws Exception {
        try {
            softAssert = new SoftAssert();
        } catch (Exception e) {
            throw new Exception("Failed : beforeMethod()" + e.getLocalizedMessage());
        }
    }

    protected Response checkTiming(Response response) {
        APIRequester.authentication = APIRequester.DEFAULT_AUTH;
        ExtentLogger.info("API took " + response.getTimeIn(TimeUnit.MILLISECONDS) + " milliseconds");
        if (checkApiPerformance) {
            softAssert.assertTrue(response.getTimeIn(TimeUnit.MILLISECONDS) <= (long)apiPerformanceSla, "API didn't met the performance SLA of " + apiPerformanceSla + " milliseconds");
        }
        return response;
    }

    protected Response makePost(String body, Headers headers, String relativeUrl)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers).body(body).when().request(Method.POST);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makePost(MultipartFile file, Headers headers, String relativeUrl) throws IOException {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Headers filteredHeaders = new Headers(headers.asList().stream()
                .filter(header -> !header.getName().equalsIgnoreCase("Content-Type"))
                .collect(Collectors.toList()));

        Response response = RestAssured.given()
                .relaxedHTTPSValidation()
                .headers(filteredHeaders)
                .multiPart("file", file.getOriginalFilename(), file.getInputStream(), file.getContentType())
                .when()
                .request(Method.POST);

        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makePost(String body, Headers headers, String relativeUrl, Map<String, String> queryParams)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers)
                .queryParams(queryParams)
                .body(body)
                .when().request(Method.POST);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makePut(String body, Headers headers, String relativeUrl)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers).body(body).when().request(Method.PUT);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makePut(String body, Headers headers, String relativeUrl, Map<String, String> queryParams)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers)
                .body(body).when().queryParams(queryParams)
                .request(Method.PUT);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makeGet( Headers headers, String relativeUrl)  {

        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers).when().request(Method.GET);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }


    protected Response makeGet( Headers headers, String relativeUrl, Map<String, String> queryParams)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation()
                .headers(headers).when()
                .queryParams(queryParams)
                .request(Method.GET);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makeDelete( Headers headers, String relativeUrl, Map<String, String> queryParams)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers)
                .when().queryParams(queryParams)
                .request(Method.DELETE);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected Response makeDelete( Headers headers, String relativeUrl)  {
        APIRequester.setBaseURI(baseURI);
        APIRequester.setBasePath(relativeUrl);

        Response response = RestAssured.given().relaxedHTTPSValidation().headers(headers).when().request(Method.DELETE);
        APIRequester.resetBasePath();
        APIRequester.resetBaseURI();

        return response;
    }

    protected void checkStatusCode(Response response) {
        softAssert.assertTrue(String.valueOf(response.getStatusCode()).startsWith("2"),
                "Expected status code starting with 2xx, but got: " + response.getStatusCode());
        softAssert.assertAll();
    }

    protected String buildRelativeUrl(String controllerName, String relativeUrl, List<String> pathVariables) throws IOException {
        if (pathVariables.isEmpty()) {
            return relativeUrl;
        }

        String filePath = "src/test/resources/data/" + controllerName + "PathVars.json";
        String jsonContent = new String(Files.readAllBytes(Paths.get(filePath)));
        JSONObject jsonObject = new JSONObject(jsonContent);

        for (String pathVariable : pathVariables) {
            Object value = jsonObject.get(pathVariable);
            relativeUrl = relativeUrl.replace("{" + pathVariable + "}", value.toString());
        }
        return relativeUrl;
    }

    protected MultipartFile uploadFile(String fileNameWithoutExtension) throws IOException {
        Path dirPath = Paths.get("src/test/resources/uploads/");
        Path filePath = null;
        String contentType = "application/octet-stream";

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath, fileNameWithoutExtension + ".*")) {
            for (Path entry : stream) {
                filePath = entry;
                contentType = Files.probeContentType(filePath);
                break;
            }
        }

        if (filePath == null) {
            throw new IOException("File not found: " + fileNameWithoutExtension);
        }

        byte[] content = Files.readAllBytes(filePath);
        return new MockMultipartFile("file", filePath.getFileName().toString(), contentType, content);
    }
}
