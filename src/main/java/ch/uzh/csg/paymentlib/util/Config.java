package ch.uzh.csg.paymentlib.util;

/**
 * This class contains settings for the payment library.
 * 
 * @author Jeton Memeti
 * 
 */
public class Config {
	
	public static final long SERVER_CALL_TIMEOUT = 3 * 1000; //in ms - server call
	public static final long SERVER_RESPONSE_TIMEOUT = 4 * 1000; //in ms - PaymentRequestHandler waiting for server response
	
	/*
	 * This flag is needed to check if debug logs should be written or not. For
	 * a release, this should always be false! Before you log something, check
	 * if this flag is set to true.
	 */
	public static final boolean DEBUG = false;

}
