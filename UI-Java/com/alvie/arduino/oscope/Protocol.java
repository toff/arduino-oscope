package com.alvie.arduino.oscope;

import java.io.*;
import java.util.*;
import gnu.io.*;

public class Protocol implements SerialPortEventListener
{
    private SerialPort port;

    int rate;
    int parity;
    int databits;
    int stopbits;
    InputStream input;
    OutputStream output;
    int b;
    int [] pBuf;
    int cksum;
    int pBufPtr;
    int pSize;
    int command;
    boolean enableTriggerDone;

    final int  COMMAND_PING             = 0x3E;
    final int  COMMAND_GET_VERSION      = 0x40;
    final int  COMMAND_START_SAMPLING   = 0x41;
    final int  COMMAND_SET_TRIGGER      = 0x42;
    final int  COMMAND_SET_HOLDOFF      = 0x43;
    final int  COMMAND_SET_VREF         = 0x45;
    final int  COMMAND_SET_PRESCALER    = 0x46;
    final int  COMMAND_GET_PARAMETERS   = 0x47;
    final int  COMMAND_SET_SAMPLES      = 0x48;
    final int  COMMAND_SET_AUTOTRIG     = 0x49;
    final int  COMMAND_SET_FLAGS        = 0x50;
    final int  COMMAND_VERSION_REPLY    = 0x80;
    final int  COMMAND_BUFFER_SEG       = 0x81;
    final int  COMMAND_PARAMETERS_REPLY = 0x87;
    final int  COMMAND_PONG             = 0xE3;
    final int  COMMAND_ERROR            = 0xFF;

    public static int FLAG_INVERT_TRIGGER  = (1<<0);
    public static int FLAG_DUAL_CHANNEL    = (1<<1);

    public interface ScopeDisplayer
    {
        void displayData(int [] buf, int size);
        void triggerDone();
        void gotParameters(int triggerLevel,int holdoffSamples, int adcref, int prescale, int numSamples, int flags);
    };

    ScopeDisplayer displayer;

    enum MyState {
        PING,
        GETVERSION,
        GETPARAMETERS,
        SAMPLING
    };

    enum PacketState {
        SIZE,
        SIZE2,
        COMMAND,
        PAYLOAD,
        CKSUM
    };

    PacketState st;
    MyState state;

    void setDisplayer(ScopeDisplayer d)
    {
        displayer = d;
    }

    Protocol()
    {
        pBuf = new int[1024];
        reset();
    }

    void reset()
    {
        st = PacketState.SIZE;
        state = MyState.PING;
        enableTriggerDone=false;
        inRequest=false;
    }

    boolean isTriggerInvert;
    boolean isDualChannel;
    boolean inRequest;
    boolean delayRequest;
    boolean freeze;

    void processPacket(int command, int [] buf, int size)
    {
        int ns;

        //System.out.println("Processing packet, command " + command);
        // System.out.println("State " + state);
        if (command==COMMAND_PARAMETERS_REPLY) {
            ns = (int)buf[4] << 8;
            ns += buf[5];

            isTriggerInvert = (buf[6] & FLAG_INVERT_TRIGGER) !=0;
            isDualChannel = (buf[6] & FLAG_DUAL_CHANNEL) !=0 ;

            if (null!=displayer)
            {
                displayer.gotParameters(buf[0],buf[1],buf[2],buf[3],ns,buf[6]);
            }
            System.out.println("Num samples: " +  ns );
        }

        switch(state) {

        case PING:
            if (command==COMMAND_PONG) {
                System.out.println("Got ping reply");
                /* Request version */
                sendPacket(COMMAND_GET_VERSION);
                state = MyState.GETVERSION;
            } else {
                System.out.println("Invalid packet " + command + " in state " + state + ", expecting " + COMMAND_PONG);
            }
            break;

        case GETVERSION:
            if (command==COMMAND_VERSION_REPLY) {
                System.out.println("Got version: OSCOPE " + buf[0] + "." + buf[1]);
                sendPacket(COMMAND_GET_PARAMETERS);
                state=MyState.GETPARAMETERS;
            } else {
                System.out.println("Invalid packet " + command);
            }
            break;

        case GETPARAMETERS:
            inRequest=true;
            sendPacket(COMMAND_START_SAMPLING);

            state = MyState.SAMPLING;
            break;

        case SAMPLING:
            if (null!=displayer) {
                displayer.displayData(buf, size);
            }

            if ( (null!=displayer && enableTriggerDone) && ! delayRequest) {
                inRequest=false;
                displayer.triggerDone();
            } else{
                if (!freeze) {
                    sendPacket(COMMAND_START_SAMPLING);
                    inRequest=true;
                }
            }
            delayRequest=false;
            state=MyState.SAMPLING;

            break;
        default:
            System.out.println("Invalid packet " + command);
        }

    }

    void setPrescaler(int prescaler)
    {
        sendPacket(COMMAND_SET_PRESCALER,prescaler);
    }

    void setVref(int vref)
    {
        sendPacket(COMMAND_SET_VREF,vref);
    }

    void setTriggerLevel(int trig)
    {
        sendPacket(COMMAND_SET_TRIGGER,trig);
    }

    void setHoldoff(int holdoff)
    {
        sendPacket(COMMAND_SET_HOLDOFF,holdoff);
    }

    void setDualChannel(boolean value)
    {
        isDualChannel = value;
        setFlags();
    }

    void setTriggerInvert(boolean value)
    {
        isTriggerInvert = value;
        setFlags();
    }

    void setFlags()
    {
        int c=0;
        if (isDualChannel)
            c|=FLAG_DUAL_CHANNEL;
        if (isTriggerInvert)
            c|=FLAG_INVERT_TRIGGER;
        sendPacket(COMMAND_SET_FLAGS,c);
    }

    void sendPacket(int command)
    {
        sendPacket(command,null,0);
    }

    void sendPacket(int command, int value)
    {
        int [] array = new int[1];
        array[0] = value;
        sendPacket(command,array,1);
    }

    void sendPacket(int command, int [] buf, int size)
    {
        int cksum=0;
        int i;
        if (null==output)
            return;
        try {
            cksum^=(size>>8)&0xff;
            cksum^=(size&0xff);

            output.write((byte)(size>>8));
            output.write(size&0xff);

            output.write(command&0xff);
            cksum^=command&0xff;


            for (i=0;i<size;i++) {
                cksum^=buf[i]&0xff;
                output.write(buf[i]&0xff);
            }

            output.write(cksum&0xff);
            output.flush();
        } catch (java.io.IOException e) {
        }
    }


    void process(int sig_bIn)
    {
        int bIn = sig_bIn & 0xff;
        cksum^=bIn;

        switch(st) {
        case SIZE:
            pSize = bIn<<8;
            cksum = bIn;
            st = PacketState.SIZE2;
            break;

        case SIZE2:
            pSize += bIn;
            pBufPtr = 0;
            st = PacketState.COMMAND;
            break;

        case COMMAND:

            command = bIn;
            if (pSize>0)
                st = PacketState.PAYLOAD;
            else
                st = PacketState.CKSUM;
            break;
        case PAYLOAD:

            pBuf[pBufPtr++] = bIn;
            pSize--;
            if (pSize==0) {
                st = PacketState.CKSUM;
            }
            break;

        case CKSUM:
            if (cksum==0) {
                processPacket(command,pBuf,pBufPtr);
            } else {
                System.out.println("Packet fails checksum check");
            }
            st = PacketState.SIZE;
        }
    }


    synchronized public void serialEvent(SerialPortEvent serialEvent) {

        if (serialEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                while (input.available() > 0) {
                    process(input.read());
                }
            } catch (IOException e) {
            }
        }
    }

    Vector getPortList()
    {
        Enumeration portList = CommPortIdentifier.getPortIdentifiers();
        Vector list = new Vector();
        while (portList.hasMoreElements()) {
            CommPortIdentifier portId =
                (CommPortIdentifier) portList.nextElement();

            if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                System.out.println("found " + portId.getName());
                list.add( portId.getName() );
            }
        }
        return list;
    }

    CommPortIdentifier getPortByName(String name)
    {
        Enumeration portList = CommPortIdentifier.getPortIdentifiers();
        while (portList.hasMoreElements()) {
            CommPortIdentifier portId =
                (CommPortIdentifier) portList.nextElement();

            if (portId.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                if (portId.getName().equals(name)) {
                    return portId;
                }
            }
        }
        return null;
    }

    class SerialPortOpenException extends java.lang.Exception
    {
    };

    void pingDevice()
    {
        int [] buf = new int[4];
        buf[0] = 1;
        buf[1] = 2;
        buf[2] = 3;
        buf[3] = 4;
        System.out.println("Pinging device");
        sendPacket(COMMAND_PING, buf, 4);
    }

    void changeSerialPort(String name) throws SerialPortOpenException
    {
        if (null!=port) {
            port.close();
        }
        reset();

        CommPortIdentifier portId = getPortByName(name);
        if (portId!=null) {
            try {
                port = (SerialPort)portId.open("oscope serial",2000);
                input = port.getInputStream();
                output = port.getOutputStream();
                port.setSerialPortParams(115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
                port.addEventListener(this);
                port.notifyOnDataAvailable(true);
            } catch (Exception e) {
                throw new SerialPortOpenException();
            }
            System.out.println("Port " + name + " open successfully");

            pingDevice();
        }
    }
};