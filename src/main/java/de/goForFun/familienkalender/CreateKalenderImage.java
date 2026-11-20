package de.goForFun.familienkalender;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import de.goForFun.familienkalender.outlook.GraphCalendarSource;
import de.goForFun.familienkalender.outlook.GraphConfig;
import de.goForFun.familienkalender.outlook.GraphSource;
import de.goForFun.familienkalender.outlook.IcsCalendarFetcher;
import de.goForFun.familienkalender.outlook.JdkGraphHttp;
import de.goForFun.familienkalender.outlook.OutlookCalendarLoader;
import de.goForFun.familienkalender.outlook.OutlookLog;
import de.goForFun.familienkalender.outlook.OutlookSourceSelector;
import de.goForFun.familienkalender.outlook.SsmRefreshTokenStore;
import net.fortuna.ical4j.data.ParserException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AWS Lambda Handler – orchestriert Kalender-Download, Parsing und Bilderstellung.
 * Lädt das gerenderte Bild als PNG (Vorschau) und als Raw-Bitplane (.bin) auf S3 hoch.
 * Die .bin Datei wird von der ServeCalendarImage Lambda über eine Function URL ausgeliefert.
 */
public class CreateKalenderImage implements RequestHandler<ScheduledEvent, Void> {

    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String CALENDAR_FEED = System.getenv("CALENDAR_FEED");
    private static final String SCHOOL_CALENDAR_FEED = System.getenv("SCHOOL_CALENDAR_FEED");
    private static final String VACATION_CALENDAR_FEED = System.getenv("VACATION_CALENDAR_FEED");
    private static final String CUSTODY_CALENDAR_FEED = System.getenv("CUSTODY_CALENDAR_FEED");
    private static final String CUSTODY_MALE_MATCH = System.getenv("CUSTODY_MALE_MATCH");
    private static final String HOLIDAY_COUNTRY = System.getenv("HOLIDAY_COUNTRY") != null
            ? System.getenv("HOLIDAY_COUNTRY") : "de";
    private static final String HOLIDAY_STATE = System.getenv("HOLIDAY_STATE") != null
            ? System.getenv("HOLIDAY_STATE") : "by";
    private static final String S3_BUCKET = "familienkalender";
    private static final String S3_KEY_PNG = "calendar.png";
    private static final String S3_KEY_BIN = "calendar.bin";

    private final ImageRenderer imageRenderer = new ImageRenderer();
    private final BitplaneExporter bitplaneExporter = new BitplaneExporter();
    private final HolidayProvider holidayProvider = new HolidayProvider(HOLIDAY_COUNTRY, HOLIDAY_STATE);

    @Override
    public Void handleRequest(ScheduledEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + input.getClass());

        try {
            LocalDateTime now = LocalDateTime.now(AppTimeZone.get());
            LocalDate today = now.toLocalDate();

            // EventRepository sammelt iCal-Events, Schulkalender, Ferien und Feiertage zentral
            URI schoolFeedUri = SCHOOL_CALENDAR_FEED != null && !SCHOOL_CALENDAR_FEED.isBlank()
                    ? URI.create(SCHOOL_CALENDAR_FEED) : null;
            URI vacationFeedUri = VACATION_CALENDAR_FEED != null && !VACATION_CALENDAR_FEED.isBlank()
                    ? URI.create(VACATION_CALENDAR_FEED) : null;
            // Original-Outlook-Feed (unanonymisiert) zusätzlich rendern und dem Participant zuordnen
            URI outlookFeedUri = parseHttpUri(System.getenv("ANON_SOURCE_FEED"));
            String outlookParticipant = System.getenv("ANON_PARTICIPANT");
            EventRepository eventRepository;
            GraphConfig graphConfig = GraphConfig.fromEnv();
            if (graphConfig.state() == GraphConfig.State.CONFIGURED) {
                OutlookLog outlookLog = lambdaOutlookLog(logger);
                OutlookCalendarLoader outlookLoader = new OutlookSourceSelector(graphConfig,
                        () -> newGraphSource(graphConfig, outlookLog), outlookFeedUri,
                        new IcsCalendarFetcher(), outlookLog);
                eventRepository = new EventRepository(URI.create(CALENDAR_FEED), schoolFeedUri, vacationFeedUri,
                        outlookLoader, outlookParticipant, today, holidayProvider);
            } else {
                if (graphConfig.state() == GraphConfig.State.PARTIAL) {
                    logger.log("WARN: Graph-Konfiguration unvollständig, Variable fehlt: "
                            + graphConfig.missingVariable() + "; Graph wird nicht verwendet");
                }
                eventRepository = new EventRepository(URI.create(CALENDAR_FEED), schoolFeedUri, vacationFeedUri,
                        outlookFeedUri, outlookParticipant, today, holidayProvider);
            }
            List<Event> todayEvents = eventRepository.getEventsForDay(today);
            List<Event> tomorrowEvents = eventRepository.getEventsForDay(today.plusDays(1));

            // 5-Wochen-Bereich für Monatskalender: ab Montag der aktuellen Woche, 35 Tage
            LocalDate calendarStart = today.with(DayOfWeek.MONDAY);
            LocalDate calendarEnd = calendarStart.plusWeeks(5).minusDays(1);
            List<Event> calendarEvents = eventRepository.getEventsForRange(calendarStart, calendarEnd);

            // Geordnete Liste aller Familienmitglieder (aus allen bekannten Events)
            List<String> participants = eventRepository.getAllParticipants();

            // Wetterdaten von OpenWeatherMap laden
            WeatherService weatherService = new WeatherService(
                    System.getenv("OPENWEATHER_API_KEY"),
                    System.getenv("OPENWEATHER_LAT"),
                    System.getenv("OPENWEATHER_LON")
            );
            List<WeatherDay> weatherDays = weatherService.fetchForecast();
            logger.log("Weather forecast: " + weatherDays.size() + " days loaded");

            // Wechselmodell-Kalender (Custody) – optional, still inaktiv ohne gültigen Feed
            Map<LocalDate, CareDay> custodySchedule = Map.of();
            List<String> errors = new ArrayList<>(eventRepository.getErrors());

            URI custodyUri = parseHttpUri(CUSTODY_CALENDAR_FEED);   // null wenn fehlt/leer/ungültig/kein http(s)
            if (custodyUri != null) {                                // R1.2 aktiv
                if (CUSTODY_MALE_MATCH == null || CUSTODY_MALE_MATCH.isBlank()) {
                    errors.add("Wechselmodell: Umgebungsvariable CUSTODY_MALE_MATCH fehlt oder ist leer"); // R2.2
                } else {
                    try {
                        List<Event> custodyEvents =
                                new IcalParser().parse(custodyUri, calendarStart, calendarEnd, EventSource.CUSTODY); // R3.1
                        custodySchedule = new CareScheduleProvider(custodyEvents, CUSTODY_MALE_MATCH).buildSchedule();
                    } catch (IOException | ParserException e) {
                        errors.add("Wechselmodell-Feed: " + e.getMessage());   // R3.6
                    }
                }
            }
            // R1.3: custodyUri == null -> schedule leer, KEIN Fehler

            RenderData renderData = new RenderData(now, todayEvents, tomorrowEvents, weatherDays,
                    calendarEvents, participants, errors, custodySchedule);

            // Feed-Fehler loggen (inkl. Custody-Fehler)
            for (String error : errors) {
                logger.log("FEED ERROR: " + error);
            }

            // Bild rendern
            BufferedImage image = imageRenderer.createImage(renderData);

            try (S3Client s3Client = S3Client.builder().region(Region.of(AWS_REGION)).build()) {

                // PNG exportieren und hochladen (für Debug/Vorschau im Browser)
                ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
                ImageIO.write(image, "png", pngOutputStream);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(S3_BUCKET)
                                .key(S3_KEY_PNG)
                                .contentType("image/png")
                                .build(),
                        RequestBody.fromBytes(pngOutputStream.toByteArray())
                );
                logger.log("PNG uploaded to s3://" + S3_BUCKET + "/" + S3_KEY_PNG);

                // Raw Bitplane exportieren und hochladen (für ESP32)
                byte[] bitplaneData = bitplaneExporter.export(image);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(S3_BUCKET)
                                .key(S3_KEY_BIN)
                                .contentType("application/octet-stream")
                                .build(),
                        RequestBody.fromBytes(bitplaneData)
                );
                logger.log("Bitplane BIN uploaded to s3://" + S3_BUCKET + "/" + S3_KEY_BIN
                        + " (" + bitplaneData.length + " bytes)");
            }
        } catch (IOException | ParserException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private static OutlookLog lambdaOutlookLog(LambdaLogger logger) {
        return new OutlookLog() {
            @Override
            public void warn(String message) {
                logger.log("WARN: " + message);
            }

            @Override
            public void error(String message) {
                logger.log("ERROR: " + message);
            }
        };
    }

    /** Erzeugt je Abruf eine Graph-Quelle; der SsmClient wird nach dem Abruf geschlossen. */
    private static GraphCalendarSource newGraphSource(GraphConfig config, OutlookLog log) {
        return window -> {
            try (SsmClient ssm = SsmClient.builder().region(Region.of(AWS_REGION)).build()) {
                return new GraphSource(config, new SsmRefreshTokenStore(ssm), new JdkGraphHttp(),
                        Clock.systemUTC(), AppTimeZone.get(), log).fetch(window);
            }
        };
    }

    /**
     * Validiert eine Feed-URL syntaktisch (R1.2/R1.3).
     *
     * @param s roher URL-String aus der Umgebungsvariable (darf null sein)
     * @return die URI, falls {@code s} eine nicht-leere, syntaktisch gültige http/https-URL
     *         mit nicht-leerem Host ist; andernfalls {@code null}. Wirft nie.
     */
    private static URI parseHttpUri(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && !u.getHost().isBlank()) {
                return u;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
