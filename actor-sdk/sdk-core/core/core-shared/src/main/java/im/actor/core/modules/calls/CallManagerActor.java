package im.actor.core.modules.calls;

import java.util.ArrayList;
import java.util.HashSet;

import im.actor.core.api.rpc.RequestCallInProgress;
import im.actor.core.api.rpc.RequestEndCall;
import im.actor.core.api.rpc.RequestSendCallSignal;
import im.actor.core.api.rpc.RequestSubscribeToCalls;
import im.actor.core.api.rpc.ResponseVoid;
import im.actor.core.entity.Peer;
import im.actor.core.entity.signals.AbsSignal;
import im.actor.core.entity.signals.AnswerSignal;
import im.actor.core.entity.signals.CandidateSignal;
import im.actor.core.entity.signals.OfferSignal;
import im.actor.core.events.NewSessionCreated;
import im.actor.core.modules.ModuleContext;
import im.actor.core.network.RpcCallback;
import im.actor.core.network.RpcException;
import im.actor.core.util.ModuleActor;
import im.actor.core.viewmodel.UserVM;
import im.actor.core.webrtc.WebRTCProvider;
import im.actor.runtime.Log;
import im.actor.runtime.eventbus.Event;
import im.actor.runtime.function.Constructor;

public class CallManagerActor extends ModuleActor {

    public static Constructor<CallManagerActor> CONSTRUCTOR(final ModuleContext context) {
        return new Constructor<CallManagerActor>() {
            @Override
            public CallManagerActor create() {
                return new CallManagerActor(context);
            }
        };
    }

    private static final String TAG = "CallManagerActor";
    private static final int KEEP_ALIVE_TIMEOUT = 10;
    private static final int KEEP_ALIVE_DELAY = 5000;

    private long subscribeRequest = -1;
    private HashSet<Long> pendingRequests = new HashSet<>();

    private WebRTCControllerImpl webRTCController;
    private WebRTCProvider provider;
    private boolean isStartedKeepAlive = false;

    private ArrayList<Candidate> pendingCandidates = new ArrayList<>();
    private boolean isReadyReceiveCandidates = false;
    private boolean isOfferRequested = false;
    private boolean isOfferReceived = false;
    private boolean isAnswerReceived = false;

    public CallManagerActor(ModuleContext context) {
        super(context);
    }

    @Override
    public void preStart() {
        super.preStart();
        subscribeForCalls();
        subscribe(NewSessionCreated.EVENT);

        webRTCController = new WebRTCControllerImpl(self());
        provider = config().getWebRTCProvider();
        provider.init(webRTCController);
    }


    //
    // Starting call
    //

    private void onIncomingCall(long callId, int uid) {
        Log.d(TAG, "onIncomingCall (" + callId + ", " + uid + ")");

        if (webRTCController.getCallId() == -1) {
            webRTCController.switchCallId(callId);
            pendingRequests.clear();
            pendingCandidates.clear();
            isReadyReceiveCandidates = false;
            isStartedKeepAlive = false;
            isOfferRequested = true; // No outgoing offer needed for ingoing call
            isOfferReceived = false;
            isAnswerReceived = true; // No incoming answers needed for ingoing call
            provider.onIncomingCall(callId, Peer.user(uid), new UserVM[]{getUserVM(uid)});
        }
    }

    private void onOutgoingCall(long callId, int uid) {
        Log.d(TAG, "onOutgoingCall (" + callId + ", " + uid + ")");

        if (webRTCController.getCallId() == -1) {
            webRTCController.switchCallId(callId);
            pendingRequests.clear();
            pendingCandidates.clear();
            isReadyReceiveCandidates = false;
            isStartedKeepAlive = false;
            isOfferRequested = false;
            isOfferReceived = true; // No offers needed for outgoing call
            isAnswerReceived = false;
            provider.onOutgoingCall(callId, Peer.user(uid), new UserVM[]{getUserVM(uid)});
        }
    }

    private void doAnswerCall(final long callId) {
        Log.d(TAG, "onAnswerCall");
        if (webRTCController.getCallId() == callId) {
            long requestId = request(new RequestCallInProgress(callId, KEEP_ALIVE_TIMEOUT), new RpcCallback<ResponseVoid>() {
                @Override
                public void onResult(ResponseVoid response) {
                    doStartKeepAlive(callId);
                }

                @Override
                public void onError(RpcException e) {
                    Log.d(TAG, "Unable to answer call. Aborting");
                    doAbortCall(callId);
                }
            });
            pendingRequests.add(requestId);
        }
    }

    private void doStartKeepAlive(long callId) {
        if (webRTCController.getCallId() == callId) {
            if (!isStartedKeepAlive) {
                isStartedKeepAlive = true;
                self().send(new DoKeepAlive(callId));
            }
        }
    }


    //
    // Call in progress
    //

    private void onReadyReceiveCandidates(long callId) {
        Log.d(TAG, "onReadyReceiveCandidates (" + callId + ")");
        if (webRTCController.getCallId() == callId) {
            if (!isReadyReceiveCandidates) {
                isReadyReceiveCandidates = true;
                for (Candidate c : pendingCandidates) {
                    provider.onCandidate(callId, c.getId(), c.getLabel(), c.getSdp());
                }
                pendingCandidates.clear();
            }
        }
    }

    private void onSignaling(long callId, byte[] message) {
        Log.d(TAG, "onSignaling (" + callId + ")");
        if (webRTCController.getCallId() == callId) {
            AbsSignal signal = AbsSignal.fromBytes(message);
            if (signal == null) {
                return;
            }

            if (signal instanceof OfferSignal) {
                OfferSignal offer = (OfferSignal) signal;
                if (!isOfferReceived) {
                    isOfferReceived = true;
                    provider.onOfferReceived(callId, offer.getSdp());
                }
            } else if (signal instanceof AnswerSignal) {
                AnswerSignal answer = (AnswerSignal) signal;
                if (!isAnswerReceived) {
                    isAnswerReceived = true;
                    provider.onAnswerReceived(callId, answer.getSdp());
                }
            } else if (signal instanceof CandidateSignal) {
                CandidateSignal candidateSignal = (CandidateSignal) signal;
                if (!isReadyReceiveCandidates) {
                    pendingCandidates.add(new Candidate(candidateSignal.getLabel(),
                            candidateSignal.getId(), candidateSignal.getSdp()));
                } else {
                    provider.onCandidate(callId, candidateSignal.getId(),
                            candidateSignal.getLabel(), candidateSignal.getSdp());
                }
            }
        }
    }

    private void doSendSignal(long callId, AbsSignal signal) {
        Log.d(TAG, "onSendSignal: " + signal);
        if (webRTCController.getCallId() == callId) {
            pendingRequests.add(request(new RequestSendCallSignal(callId, signal.toByteArray())));
        }
    }

    private void doKeepAlive(long callId) {
        Log.d(TAG, "doKeepAlive (" + callId + ")");
        if (webRTCController.getCallId() == callId) {
            pendingRequests.add(request(new RequestCallInProgress(callId, KEEP_ALIVE_TIMEOUT)));
            self().send(new DoKeepAlive(callId), KEEP_ALIVE_DELAY);
        }
    }

    private void onKeepAlive(long callId, int timeout) {
        if (webRTCController.getCallId() == callId) {
            if (!isOfferRequested) {
                isOfferRequested = true;
                provider.onOfferNeeded(callId);
            }

            // TODO: Auto kill call on timeout
        }
    }

    //
    // Ending call
    //

    private void onCallEnded(long callId) {
        Log.d(TAG, "onCallEnded: " + callId);
        doAbortCall(callId);
    }

    private void doEndCall(long callId) {
        Log.d(TAG, "endCall: " + callId);
        if (webRTCController.getCallId() == callId) {
            request(new RequestEndCall(callId));
        }
        doAbortCall(callId);
    }

    private void doAbortCall(long callId) {
        if (webRTCController.getCallId() == callId) {
            webRTCController.clearCallId();
            provider.onCallEnd(callId);
            for (long r : pendingRequests) {
                context().getActorApi().cancelRequest(r);
            }
            pendingRequests.clear();
        }
    }


    //
    // Subscribing for call events
    //

    private void subscribeForCalls() {
        if (subscribeRequest != -1) {
            context().getActorApi().cancelRequest(subscribeRequest);
            subscribeRequest = -1;
        }
        subscribeRequest = request(new RequestSubscribeToCalls());
    }

    @Override
    public void onBusEvent(Event event) {
        if (NewSessionCreated.EVENT.equals(event.getType())) {
            subscribeForCalls();
        } else {
            super.onBusEvent(event);
        }
    }


    //
    // Messages
    //

    @Override
    public void onReceive(Object message) {
        if (message instanceof OnIncomingCall) {
            OnIncomingCall call = (OnIncomingCall) message;
            onIncomingCall(call.getCallId(), call.getUid());
        } else if (message instanceof OnOutgoingCall) {
            OnOutgoingCall call = (OnOutgoingCall) message;
            onOutgoingCall(call.getCallId(), call.getUid());
        } else if (message instanceof OnSignaling) {
            OnSignaling signaling = (OnSignaling) message;
            onSignaling(signaling.getCallId(), signaling.getMessage());
        } else if (message instanceof AnswerCall) {
            doAnswerCall(((AnswerCall) message).getCallId());
        } else if (message instanceof SendSignaling) {
            doSendSignal(((SendSignaling) message).getCallId(), ((SendSignaling) message).getSignal());
        } else if (message instanceof EndCall) {
            doEndCall(((EndCall) message).getCallId());
        } else if (message instanceof OnCallEnded) {
            onCallEnded(((OnCallEnded) message).getCallId());
        } else if (message instanceof DoKeepAlive) {
            doKeepAlive(((DoKeepAlive) message).getCallId());
        } else if (message instanceof OnCallInProgress) {
            OnCallInProgress call = (OnCallInProgress) message;
            onKeepAlive(call.getCallId(), call.getTimeout());
        } else if (message instanceof ReadyForCandidates) {
            ReadyForCandidates readyForCandidates = (ReadyForCandidates) message;
            onReadyReceiveCandidates(readyForCandidates.getCallId());
        } else {
            super.onReceive(message);
        }
    }

    public static class OnIncomingCall {

        private long callId;
        private int uid;

        public OnIncomingCall(long callId, int uid) {
            this.callId = callId;
            this.uid = uid;
        }

        public long getCallId() {
            return callId;
        }

        public int getUid() {
            return uid;
        }
    }

    public static class OnOutgoingCall {

        private long callId;
        private int uid;

        public OnOutgoingCall(long callId, int uid) {
            this.callId = callId;
            this.uid = uid;
        }

        public long getCallId() {
            return callId;
        }

        public int getUid() {
            return uid;
        }
    }

    public static class OnSignaling {
        private long callId;
        private byte[] message;

        public OnSignaling(long callId, byte[] message) {
            this.callId = callId;
            this.message = message;
        }

        public long getCallId() {
            return callId;
        }

        public byte[] getMessage() {
            return message;
        }
    }

    public static class OnCallEnded {
        private long callId;

        public OnCallEnded(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    public static class OnCallInProgress {

        private long callId;
        private int timeout;

        public OnCallInProgress(long callId, int timeout) {
            this.callId = callId;
            this.timeout = timeout;
        }

        public long getCallId() {
            return callId;
        }

        public int getTimeout() {
            return timeout;
        }
    }

    public static class AnswerCall {

        private long callId;

        public AnswerCall(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    public static class EndCall {
        private long callId;

        public EndCall(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    public static class SendSignaling {

        private long callId;
        private AbsSignal signal;

        public SendSignaling(long callId, AbsSignal signal) {
            this.callId = callId;
            this.signal = signal;
        }

        public long getCallId() {
            return callId;
        }

        public AbsSignal getSignal() {
            return signal;
        }
    }

    public static class ReadyForCandidates {
        private long callId;

        public ReadyForCandidates(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    public static class MediaStreamsReady {
        private long callid;

        public MediaStreamsReady(long callid) {
            this.callid = callid;
        }

        public long getCallid() {
            return callid;
        }
    }

    private static class DoKeepAlive {
        private long callId;

        public DoKeepAlive(long callId) {
            this.callId = callId;
        }

        public long getCallId() {
            return callId;
        }
    }

    private static class Candidate {

        private int label;
        private String id;
        private String sdp;

        public Candidate(int label, String id, String sdp) {
            this.label = label;
            this.id = id;
            this.sdp = sdp;
        }

        public int getLabel() {
            return label;
        }

        public String getId() {
            return id;
        }

        public String getSdp() {
            return sdp;
        }
    }
}