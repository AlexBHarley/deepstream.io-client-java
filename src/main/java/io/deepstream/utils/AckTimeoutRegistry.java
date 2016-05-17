package io.deepstream.utils;

import io.deepstream.ConnectionChangeListener;
import io.deepstream.IDeepstreamClient;
import io.deepstream.constants.Actions;
import io.deepstream.constants.ConnectionState;
import io.deepstream.constants.Event;
import io.deepstream.constants.Topic;
import io.deepstream.message.Message;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class AckTimeoutRegistry implements AckTimeoutCallback, ResubscribeCallback, ConnectionChangeListener {

    private Map<String, AckTimeoutTask> register;
    private Timer timer;
    private Topic topic;
    private long timeoutDuration;
    private IDeepstreamClient client;
    private ConnectionState state;
    private LinkedBlockingQueue<AckTimeoutTask> ackTimers;

    public AckTimeoutRegistry(IDeepstreamClient client, Topic topic, long timeoutDuration ) {
        this.client = client;
        this.state = client.getConnectionState();
        this.register = new HashMap<String, AckTimeoutTask>();
        this.ackTimers = new LinkedBlockingQueue<AckTimeoutTask>();
        this.timer = new Timer();
        this.topic = topic;
        this.timeoutDuration = timeoutDuration;
    }

    /**
     * Clears the ack timeout for a message once.
     *
     * @param message The message received to remove the ack timer for
     */
    public void clear( Message message ) {
        String name = message.data[ 1 ];
        String uniqueName = message.data[ 0 ] + name;

        AckTimeoutTask task = register.get( uniqueName );
        if( task == null ) {
            task = register.get( name );
        }
        if( task != null ) {
            task.cancel();
        } else {
            this.client.onError( this.topic, Event.UNSOLICITED_MESSAGE, message.raw);
        }
    }

    /**
     * Checks to see if an ack timer already exists for the given name. If
     * it does, it clears it, then starts a new one.
     *
     * @param name The name to be added to the register
     */
    public void add( String name ) {
        AckTimeoutTask task = this.register.get( name );
        if( task != null ) {
            Message m = new Message(null, null, null, new String[]{} );
            m.data[0] = name;
            clear( m );
        }
        addToRegister( name );
    }

    /**
     * Checks to see if an ack timer already exists for the given name
     * and action. If it does, it clears it, then starts a new one.
     *
     * @param name The name to be added to the register
     * @param action The action to be added to the register
     */
    public void add( String name, Actions action ) {
        String uniqueName = ( action != null ) ? action + name : name;

        AckTimeoutTask task = this.register.get( uniqueName );
        if( task != null ) {
            Message m = new Message(null, null, null, new String[]{} );
            if( action == null ) {
                m.data[0] = name;
            } else {
                m.data[0] = action.name();
                m.data[1] = name;
            }
            clear( m );
        }
        addToRegister( uniqueName );
    }

    /**
     * Adds the uniqueName to the register and starts the timer if the
     * connection state is OPEN.
     *
     * @param uniqueName The name to be added to the register
     */
    private void addToRegister( String uniqueName ) {
        AckTimeoutTask task = new AckTimeoutTask( uniqueName, this );
        register.put( uniqueName, task );
        if( this.state == ConnectionState.OPEN ) {
            timer.schedule( task, this.timeoutDuration );
        } else {
            this.ackTimers.add( task );
        }
    }

    @Override
    public void onTimeout( String name ) {
        this.register.remove( name );
        String msg = "No ACK message received in time for " + name;
        this.client.onError( this.topic, Event.ACK_TIMEOUT, msg );
        //this.emit( 'timeout', name );
    }

    @Override
    public void resubscribe() {
        AckTimeoutTask task = null;
        while( this.ackTimers.peek() != null ) {
            try {
                task = this.ackTimers.take();
            } catch (InterruptedException e) {}
            
            if( task != null ) {
                this.timer.schedule( task, this.timeoutDuration );
            }
        }
    }

    @Override
    public void connectionStateChanged(ConnectionState connectionState) {
        if( connectionState == ConnectionState.OPEN ) {
            resubscribe();
        }
        this.state = connectionState;
    }
}