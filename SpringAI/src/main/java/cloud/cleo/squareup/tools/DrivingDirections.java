package cloud.cleo.squareup.tools;


/**
 * Shared base for driving directions tools.
 */
public abstract class DrivingDirections extends AbstractTool {

    /**
     * URL for driving directions with Place ID so it comes up as Copper Fox properly for the pin.
     */
    protected static final String DRIVING_DIRECTIONS_URL =
            "google.com/maps/dir/?api=1&destination=160+Main+St+Wahkon+MN+56386&destination_place_id=ChIJWxVcpjffs1IRcSX7D8pJSUY";
}

