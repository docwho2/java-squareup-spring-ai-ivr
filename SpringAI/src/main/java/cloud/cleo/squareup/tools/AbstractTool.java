/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.tools;

import com.squareup.square.AsyncSquareClient;
import com.squareup.square.core.Environment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base for all Tools.
 *
 * @author sjensen
 */
public abstract class AbstractTool {

    // Initialize the Log4j logger.
    protected static final Logger log = LogManager.getLogger(AbstractTool.class);

    @Getter
    private final static boolean squareEnabled;
    @Getter
    private final static AsyncSquareClient squareClient;

    protected static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    static {
        final var key = System.getenv("SQUARE_API_KEY");
        final var loc = System.getenv("SQUARE_LOCATION_ID");
        final var senv = System.getenv("SQUARE_ENVIRONMENT");

        squareEnabled = !((loc == null || loc.isBlank() || loc.equalsIgnoreCase("DISABLED")) || (key == null || key.isBlank() || key.equalsIgnoreCase("DISABLED")));
        //log.debug("Square Enabled = " + squareEnabled);

        // If square enabled, then configure the client
        if (squareEnabled) {
            squareClient = AsyncSquareClient.builder()
                    .token(key)
                    .environment(switch (senv) {
                default ->
                    Environment.PRODUCTION;
                case "SANDBOX", "sandbox" ->
                    Environment.SANDBOX;
            }).build();
        } else {
            squareClient = null;
        }
    }
    
    

    /**
     * When this function is called, will this result in ending the current session and returning control back to Chime.
     * IE, hang up, transfer, etc. This should all be voice related since you never terminate a text session, lex will
     * time it out based on it's setting.
     *
     * @return
     */
    public boolean isTerminating() {
        return false;
    }
}
