package cloud.cleo.squareup.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class StoreWeatherServiceTest {

    private static final String WEATHER = """
            {
              "current_weather": {"temperature": 33.9, "windspeed": 14.4, "weathercode": 3},
              "daily": {
                "time": ["2026-11-16"],
                "temperature_2m_min": [33.6],
                "temperature_2m_max": [52.1],
                "weathercode": [3]
              }
            }
            """;

    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        releaseResponse.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
            executor.close();
        }
    }

    @Test
    void returnsCurrentWeatherAndForecast() throws IOException {
        var service = startServer(exchange -> respond(exchange, 200, WEATHER));

        var result = service.getStoreWeather();

        assertEquals("Cloudy", result.summary());
        assertEquals(33.9, result.temperatureFahrenheit());
        assertEquals(14.4, result.windSpeedMilesPerHour());
        assertEquals(1, result.forecast().size());
        assertEquals(52.1, result.forecast().getFirst().maxTempFahrenheit());
    }

    @Test
    void returnsUnavailableWhenServerNeverSendsHeadersAndRecoversOnNextRequest() throws IOException {
        var service = startServer(exchange -> {
            if (requests.get() == 1) {
                awaitRelease();
            } else {
                respond(exchange, 200, WEATHER);
            }
        });

        // Use the production timeouts and a real socket, without calling the weather provider.
        var result = assertTimeoutPreemptively(Duration.ofSeconds(8), service::getStoreWeather);

        assertUnavailable(result);
        assertEquals(1, requests.get());

        var recovered = service.getStoreWeather();
        assertEquals(33.9, recovered.temperatureFahrenheit());
        assertEquals("Cloudy", recovered.summary());
        assertEquals(2, requests.get());
    }

    @Test
    void returnsUnavailableWhenResponseBodyStalls() throws IOException {
        var service = startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, WEATHER.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            awaitRelease();
        });

        var result = assertTimeoutPreemptively(Duration.ofSeconds(8), service::getStoreWeather);

        assertUnavailable(result);
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @CsvSource({"503, unavailable", "429, rate-limited", "200, invalid-json", "200, {}"})
    void returnsUnavailableForHttpErrorsOrInvalidData(int status, String body) throws IOException {
        var service = startServer(exchange -> respond(exchange, status, body));

        assertUnavailable(service.getStoreWeather());
        assertEquals(1, requests.get());
    }

    @Test
    void preservesCurrentConditionsWhenForecastIsMissing() throws IOException {
        var service = startServer(exchange -> respond(exchange, 200, """
                {"current_weather": {"temperature": 33.9, "windspeed": 14.4, "weathercode": 3}}
                """));

        var result = service.getStoreWeather();

        assertEquals("Cloudy (forecast unavailable).", result.summary());
        assertEquals(33.9, result.temperatureFahrenheit());
        assertEquals(14.4, result.windSpeedMilesPerHour());
        assertTrue(result.forecast().isEmpty());
    }

    private StoreWeatherService startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/forecast", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return new StoreWeatherService(ZoneId.of("America/Chicago"),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void awaitRelease() {
        try {
            releaseResponse.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void assertUnavailable(StoreWeatherService.StoreWeatherResponse result) {
        assertEquals("Wahkon, MN", result.locationName());
        assertEquals("Weather is temporarily unavailable. Please try again later.", result.summary());
        assertNull(result.temperatureFahrenheit());
        assertNull(result.windSpeedMilesPerHour());
        assertTrue(result.forecast().isEmpty());
    }
}
