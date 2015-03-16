package ch.uzh.csg.paymentlib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.Charset;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import android.app.Activity;
import android.util.Log;
import ch.uzh.csg.coinblesk.customserialization.Currency;
import ch.uzh.csg.coinblesk.customserialization.DecoderFactory;
import ch.uzh.csg.coinblesk.customserialization.InitMessagePayee;
import ch.uzh.csg.coinblesk.customserialization.PKIAlgorithm;
import ch.uzh.csg.coinblesk.customserialization.PaymentRequest;
import ch.uzh.csg.coinblesk.customserialization.PaymentResponse;
import ch.uzh.csg.coinblesk.customserialization.ServerPaymentRequest;
import ch.uzh.csg.coinblesk.customserialization.ServerPaymentResponse;
import ch.uzh.csg.coinblesk.customserialization.ServerResponseStatus;
import ch.uzh.csg.nfclib.NfcInitiator;
import ch.uzh.csg.nfclib.events.NfcEvent;
import ch.uzh.csg.paymentlib.PaymentRequestInitializer.PaymentType;
import ch.uzh.csg.paymentlib.container.PaymentInfos;
import ch.uzh.csg.paymentlib.container.ServerInfos;
import ch.uzh.csg.paymentlib.container.UserInfos;
import ch.uzh.csg.paymentlib.messages.PaymentError;
import ch.uzh.csg.paymentlib.messages.PaymentMessage;
import ch.uzh.csg.paymentlib.testutils.PersistencyHandler;
import ch.uzh.csg.paymentlib.testutils.TestUtils;
import ch.uzh.csg.paymentlib.util.Config;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Log.class)
public class PaymentRequestInitializerTest {
	
	private Activity hostActivity = Mockito.mock(Activity.class);
	
	private class State {
		private PaymentEvent event;
		private Object object;
		@SuppressWarnings("unused")
		private IServerResponseListener caller;
		
		private State(PaymentEvent event, Object object, IServerResponseListener caller) {
			this.event = event;
			this.object = object;
			this.caller = caller;
		}
	}
	
	private List<State> states = new ArrayList<State>();
	private PersistencyHandler persistencyHandler;
	
	private KeyPair keyPairServer;
	private PaymentRequestInitializer pri;
	
	private boolean serverRefuse;
	private boolean serverTimeout;
	
	private void reset() {
		states.clear();
		persistencyHandler = new PersistencyHandler();
		
		serverRefuse = false;
		serverTimeout = false;
	}
	
	private IPaymentEventHandler paymentEventHandler = new IPaymentEventHandler() {
		
		@Override
		public void handleMessage(PaymentEvent event, Object object, IServerResponseListener caller) {
			states.add(new State(event, object, caller));
			
			if (event == PaymentEvent.FORWARD_TO_SERVER) {
				if (serverTimeout) {
					try {
						Thread.sleep(Config.SERVER_CALL_TIMEOUT+100);
					} catch (InterruptedException e) {
					}
				}
				
				if (serverRefuse) {
					try {
						assertTrue(object instanceof byte[]);
						ServerPaymentRequest decode = DecoderFactory.decode(ServerPaymentRequest.class, (byte[]) object);
						PaymentRequest paymentRequestPayer = decode.getPaymentRequestPayer();
						
						String reason = "I don't like you";
						PaymentResponse pr = new PaymentResponse(PKIAlgorithm.DEFAULT, 1, ServerResponseStatus.FAILURE, reason, paymentRequestPayer.getUsernamePayer(), paymentRequestPayer.getUsernamePayee(), paymentRequestPayer.getCurrency(), paymentRequestPayer.getAmount(), paymentRequestPayer.getTimestamp());
						pr.sign(keyPairServer.getPrivate());
						pri.onServerResponse(new ServerPaymentResponse(pr));
					} catch (Exception e) {
						assertTrue(false);
					}
				} else {
					try {
						assertTrue(object instanceof byte[]);
						ServerPaymentRequest decode = DecoderFactory.decode(ServerPaymentRequest.class, (byte[]) object);
						PaymentRequest paymentRequestPayer = decode.getPaymentRequestPayer();
						
						PaymentResponse pr = new PaymentResponse(PKIAlgorithm.DEFAULT, 1, ServerResponseStatus.SUCCESS, null, paymentRequestPayer.getUsernamePayer(), paymentRequestPayer.getUsernamePayee(), paymentRequestPayer.getCurrency(), paymentRequestPayer.getAmount(), paymentRequestPayer.getTimestamp());
						pr.sign(keyPairServer.getPrivate());
						pri.onServerResponse(new ServerPaymentResponse(pr));
					} catch (Exception e) {
						assertTrue(false);
					}
				}
			}
		}
	};
	
	@Before
	public void before() {
		PowerMockito.mockStatic(Log.class);
		Answer<Integer> answer = new Answer<Integer>() {
			@Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
	            System.err.println(Arrays.toString(invocation.getArguments()));
	            return 0;
            }
		};
		PowerMockito.when(Log.i(Mockito.anyString(), Mockito.anyString())).then(answer);
		PowerMockito.when(Log.d(Mockito.anyString(), Mockito.anyString())).then(answer);
		PowerMockito.when(Log.e(Mockito.anyString(), Mockito.anyString())).then(answer);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payee_PayerRefuses() throws Exception {
		/*
		 * Simulates payee rejects the payment.
		 */
		reset();
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfos = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		final PaymentRequestInitializer pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfos, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				byte[] response = new PaymentMessage().error().payload(new byte[] { PaymentError.PAYER_REFUSED.getCode() }).bytes();
				assertNotNull(response);
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver).transceive(any(byte[].class));
		
		assertEquals(2, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.PAYER_REFUSED, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payee_PayerModifiesRequest() throws Exception {
		/*
		 * Simulates payer modifies the payment request (e.g. the amount).
		 */
		reset();
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		final UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		final PaymentRequestInitializer pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayee, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				InitMessagePayee initMessage = DecoderFactory.decode(InitMessagePayee.class, pm.payload());
				
				PaymentRequest pr = new PaymentRequest(userInfosPayer.getPKIAlgorithm(), userInfosPayer.getKeyNumber(), userInfosPayer.getUsername(), initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount()+1, System.currentTimeMillis());
				pr.sign(userInfosPayer.getPrivateKey());
				
				byte[] response = new PaymentMessage().payload(pr.encode()).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		}).doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertTrue(pm.isError());
				
				byte[] response = new PaymentMessage().error().payload(new byte[] { pm.payload()[0] }).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(2)).transceive(any(byte[].class));
		
		assertEquals(2, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.REQUESTS_NOT_IDENTIC, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payee_ServerRefuses() throws Exception {
		/*
		 * Simulates server refuses the payment for any reason
		 */
		reset();
		serverRefuse = true;
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		final UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayee, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				InitMessagePayee initMessage = DecoderFactory.decode(InitMessagePayee.class, pm.payload());
				
				PaymentRequest pr = new PaymentRequest(userInfosPayer.getPKIAlgorithm(), userInfosPayer.getKeyNumber(), userInfosPayer.getUsername(), initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount(), System.currentTimeMillis());
				pr.sign(userInfosPayer.getPrivateKey());
				
				byte[] response = new PaymentMessage().payload(pr.encode()).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		Stubber stubber2 = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				PaymentResponse pr = DecoderFactory.decode(PaymentResponse.class, pm.payload());
				assertNotNull(pr);
				assertEquals(ServerResponseStatus.FAILURE, pr.getStatus());
				
				byte[] response = new PaymentMessage().payload(PaymentRequestHandler.ACK).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber2.when(transceiver).sendLater(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(1)).transceive(any(byte[].class));
		verify(transceiver, times(1)).sendLater(any(byte[].class));
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.SERVER_REFUSED, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payee_Success() throws Exception {
		/*
		 * Simulates a successful payment
		 */
		reset();
		serverRefuse = false;
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		final UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayee, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				InitMessagePayee initMessage = DecoderFactory.decode(InitMessagePayee.class, pm.payload());
				
				PaymentRequest pr = new PaymentRequest(userInfosPayer.getPKIAlgorithm(), userInfosPayer.getKeyNumber(), userInfosPayer.getUsername(), initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount(), System.currentTimeMillis());
				pr.sign(userInfosPayer.getPrivateKey());
				
				byte[] response = new PaymentMessage().payload(pr.encode()).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		Stubber stubber2 = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				PaymentResponse pr = DecoderFactory.decode(PaymentResponse.class, pm.payload());
				assertNotNull(pr);
				assertEquals(ServerResponseStatus.SUCCESS, pr.getStatus());
				
				byte[] response = new PaymentMessage().payload(PaymentRequestHandler.ACK).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber2.when(transceiver).sendLater(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(1)).transceive(any(byte[].class));
		verify(transceiver, times(1)).sendLater(any(byte[].class));
		
		//assure that the timeout is not thrown
		Thread.sleep(Config.SERVER_CALL_TIMEOUT+500);
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.SUCCESS, state.event);
		assertTrue(state.object instanceof PaymentResponse);
		PaymentResponse pr = (PaymentResponse) state.object;
		assertEquals(userInfosPayer.getUsername(), pr.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), pr.getUsernamePayee());
	}
	
	@Test
	public void testPaymentRequestInitializer_Payee_ServerCallTimeout() throws Exception {
		/*
		 * Simulates a server timeout
		 */
		reset();
		serverTimeout = true;
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		final UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayee, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				InitMessagePayee initMessage = DecoderFactory.decode(InitMessagePayee.class, pm.payload());
				
				PaymentRequest pr = new PaymentRequest(userInfosPayer.getPKIAlgorithm(), userInfosPayer.getKeyNumber(), userInfosPayer.getUsername(), initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount(), System.currentTimeMillis());
				pr.sign(userInfosPayer.getPrivateKey());
				
				byte[] response = new PaymentMessage().payload(pr.encode()).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		}).doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertTrue(pm.isError());
				assertEquals(1, pm.payload().length);
				assertEquals(PaymentError.NO_SERVER_RESPONSE.getCode(), pm.payload()[0]);
				
				byte[] response = new PaymentMessage().error().payload(new byte[] { pm.payload()[0] }).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		Thread.sleep(Config.SERVER_CALL_TIMEOUT+500);
		
		// simulate a response which comes to late, if this runs through its
		// fine, if a NPE is thrown, than something is not ok
		pri.onServerResponse(null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(2)).transceive(any(byte[].class));
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.NO_SERVER_RESPONSE, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payer_ServerRefuses() throws Exception {
		/*
		 * Simulates server refuses the payment for any reason
		 */
		reset();
		serverRefuse = true;
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayer = new UserInfos("payer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1, System.currentTimeMillis());
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		final UserInfos userInfosPayee = new UserInfos("payee", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayer, paymentInfos, serverInfos, persistencyHandler, PaymentType.SEND_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				assertTrue(pm.isPayer());
				
				byte[] bytes = userInfosPayee.getUsername().getBytes(Charset.forName("UTF-8"));
				byte[] response = new PaymentMessage().payee().payload(bytes).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		Stubber stubber2 = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				assertTrue(pm.isPayer());
				
				PaymentResponse pr = DecoderFactory.decode(PaymentResponse.class, pm.payload());
				assertNotNull(pr);
				assertEquals(ServerResponseStatus.FAILURE, pr.getStatus());
				
				byte[] response = new PaymentMessage().payload(PaymentRequestHandler.ACK).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber2.when(transceiver).sendLater(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(1)).transceive(any(byte[].class));
		verify(transceiver, times(1)).sendLater(any(byte[].class));
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.SERVER_REFUSED, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_Payer_Success() throws Exception {
		/*
		 * Simulates a successful payment
		 */
		reset();
		serverRefuse = false;
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayer = new UserInfos("seller", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1, System.currentTimeMillis());
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		final UserInfos userInfosPayee = new UserInfos("buyer", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayer, paymentInfos, serverInfos, persistencyHandler, PaymentType.SEND_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				assertTrue(pm.isPayer());
				
				byte[] bytes = userInfosPayee.getUsername().getBytes(Charset.forName("UTF-8"));
				byte[] response = new PaymentMessage().payee().payload(bytes).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		Stubber stubber2 = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				assertTrue(pm.isPayer());
				
				PaymentResponse pr = DecoderFactory.decode(PaymentResponse.class, pm.payload());
				assertNotNull(pr);
				assertEquals(ServerResponseStatus.SUCCESS, pr.getStatus());
				
				byte[] response = new PaymentMessage().payload(PaymentRequestHandler.ACK).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber2.when(transceiver).sendLater(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(1)).transceive(any(byte[].class));
		verify(transceiver, times(1)).sendLater(any(byte[].class));
		
		//assure that the timeout is not thrown
		Thread.sleep(Config.SERVER_CALL_TIMEOUT+500);
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.SUCCESS, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentResponse);
		PaymentResponse pr = (PaymentResponse) state.object;
		assertEquals(userInfosPayer.getUsername(), pr.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), pr.getUsernamePayee());
	}
	
	@Test
	public void testPaymentRequestInitializer_Payer_ServerCallTimeout() throws Exception {
		/*
		 * Simulates a server timeout
		 */
		reset();
		serverTimeout = true;
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfosPayer = new UserInfos("seller", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1, System.currentTimeMillis());
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		final UserInfos userInfosPayee = new UserInfos("buyer", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfosPayer, paymentInfos, serverInfos, persistencyHandler, PaymentType.SEND_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				assertTrue(pm.isPayer());
				
				byte[] bytes = userInfosPayee.getUsername().getBytes(Charset.forName("UTF-8"));
				byte[] response = new PaymentMessage().payee().payload(bytes).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		}).doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertTrue(pm.isError());
				assertEquals(1, pm.payload().length);
				assertEquals(PaymentError.NO_SERVER_RESPONSE.getCode(), pm.payload()[0]);
				
				byte[] response = new PaymentMessage().error().payload(new byte[] { pm.payload()[0] }).bytes();
				assertNotNull(response);
				
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		Thread.sleep(Config.SERVER_CALL_TIMEOUT+500);
		
		assertEquals(1, persistencyHandler.getList().size());
		
		verify(transceiver, times(2)).transceive(any(byte[].class));
		
		assertEquals(3, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.FORWARD_TO_SERVER, state.event);
		assertNotNull(state.object);
		state = states.get(2);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.NO_SERVER_RESPONSE, err);
	}
	
	@Test
	public void testPaymentRequestInitializer_IllegalVersion() throws Exception {
		reset();
		
		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		
		UserInfos userInfos = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		NfcInitiator transceiver = mock(NfcInitiator.class);
		
		final PaymentRequestInitializer pri = new PaymentRequestInitializer(hostActivity, transceiver, paymentEventHandler, userInfos, paymentInfos, serverInfos, persistencyHandler, PaymentType.REQUEST_PAYMENT);
		
		Stubber stubber = doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertFalse(pm.isError());
				
				byte header = (byte) 0xC0; // 11000000
				byte[] response = new PaymentMessage().bytes(new byte[] { header }).bytes();
				assertNotNull(response);
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response);
				return null;
			}
		}).doAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				Object[] arguments = invocation.getArguments();
				
				PaymentMessage pm = new PaymentMessage().bytes((byte[]) arguments[0]);
				assertTrue(pm.isError());
				
				byte header = (byte) 0xC0; // 11000000
				PaymentMessage response = new PaymentMessage().bytes(new byte[] { header });
				response = response.error().payload(new byte[] { PaymentError.INCOMPATIBLE_VERSIONS.getCode() });
				
				assertNotNull(response);
				pri.getNfcEventHandler().handleMessage(NfcEvent.MESSAGE_RECEIVED, response.bytes());
				return null;
			}
		});
		stubber.when(transceiver).transceive(any(byte[].class));
		
		//start test case manually, since this would be started on an nfc contact!
		pri.getNfcEventHandler().handleMessage(NfcEvent.INITIALIZED, null);
		
		assertEquals(0, persistencyHandler.getList().size());
		
		verify(transceiver, times(2)).transceive(any(byte[].class));
		
		assertEquals(2, states.size());
		State state = states.get(0);
		assertEquals(PaymentEvent.INITIALIZED, state.event);
		state = states.get(1);
		assertEquals(PaymentEvent.ERROR, state.event);
		assertNotNull(state.object);
		assertTrue(state.object instanceof PaymentError);
		PaymentError err = (PaymentError) state.object;
		assertEquals(PaymentError.INCOMPATIBLE_VERSIONS, err);
	}
	
}
