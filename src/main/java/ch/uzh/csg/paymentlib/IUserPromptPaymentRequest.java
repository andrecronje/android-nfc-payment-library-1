package ch.uzh.csg.paymentlib;

import ch.uzh.csg.coinblesk.customserialization.Currency;

/**
 * The user of this interface can check if the application user accepted or
 * rejected a payment request (by clicking the appropriate button on the UI) or
 * prompt the user to do so.
 * 
 * @author Jeton Memeti
 * 
 */
public interface IUserPromptPaymentRequest {

	/**
	 * Prompts the application user if he wants to accept or reject the payment
	 * with the given parameters. The user's answer has to be passed to the
	 * answer object as soon as the user has decided (e.g., clicked on a
	 * button).
	 * 
	 * @param username
	 *            the payee's username
	 * @param currency
	 *            the payment {@link Currency}
	 * @param amount
	 *            the payment amount in the given currency
	 * @param answer
	 *            the {@link IUserPromptPaymentRequest} to pass the user's
	 *            answer once the user decided if he wants to accept or reject
	 *            the payment request
	 */
	public void promptUserPaymentRequest(String username, Currency currency, long amount, IUserPromptAnswer answer);
	
	/**
	 * Returns the application user's decision which has been asked already
	 * before (see getPaymentRequestAnswer). This avoids prompting the user
	 * again (e.g., with a dialog) and returns the already entered decision.
	 * 
	 * @return true if the user has accepted the payment, false otherwise
	 */
	public boolean isPaymentAccepted();
	
}
