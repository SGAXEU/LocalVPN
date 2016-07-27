/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package xyz.hexene.localvpn;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import xyz.hexene.localvpn.TCB.TCBStatus;

public class TCPInput implements Runnable
{
    private static final String TAG = TCPInput.class.getSimpleName();
    private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;
    private LocalVPNService localVPNService;



    public TCPInput(ConcurrentLinkedQueue<ByteBuffer> outputQueue, Selector selector, LocalVPNService localVPNService)
    {
        this.outputQueue = outputQueue;
        this.selector = selector;
        this.localVPNService = localVPNService;
    }

    @Override
    public void run()
    {
        try
        {
            Log.d(TAG, "Started");
            while (!Thread.interrupted())
            {
                int readyChannels = selector.select();

                if (readyChannels == 0) {
                    Thread.sleep(10);
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = keys.iterator();

                while (keyIterator.hasNext() && !Thread.interrupted())
                {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid())
                    {
                        if (key.isConnectable())
                            processConnect(key, keyIterator);
                        else if (key.isReadable())
                            processInput(key, keyIterator);
                    }
                }
            }
        }
        catch (InterruptedException e)
        {
            Log.i(TAG, "Stopping");
        }
        catch (IOException e)
        {
            Log.w(TAG, e.toString(), e);
        }
    }

    private void processConnect(SelectionKey key, Iterator<SelectionKey> keyIterator)
    {
        TCB tcb = (TCB) key.attachment();
        Packet referencePacket = tcb.referencePacket;
        try
        {
            if (tcb.channel.finishConnect())
            {
                keyIterator.remove();
                tcb.status = TCBStatus.SYN_RECEIVED;

                // TODO: Set MSS for receiving larger packets from the device
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                outputQueue.offer(responseBuffer);

                tcb.mySequenceNum++; // SYN counts as a byte
                key.interestOps(SelectionKey.OP_READ);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "Connection error: " + tcb.ipAndPort+ " " + e.toString());
            ByteBuffer responseBuffer = ByteBufferPool.acquire();
            referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
            outputQueue.offer(responseBuffer);
            TCB.closeTCB(tcb);
        }
    }

    private void processInput(SelectionKey key, Iterator<SelectionKey> keyIterator)
    {
        keyIterator.remove();
        ByteBuffer receiveBuffer = ByteBufferPool.acquire();
        // Leave space for the header
        receiveBuffer.position(HEADER_SIZE);

        TCB tcb = (TCB) key.attachment();
        synchronized (tcb)
        {
            Packet referencePacket = tcb.referencePacket;
            SocketChannel inputChannel = (SocketChannel) key.channel();
            int readBytes;

            InetAddress destinationAddress = referencePacket.ip4Header.destinationAddress;
            Packet.TCPHeader tcpHeader = referencePacket.tcpHeader;
            int destinationPort = tcpHeader.destinationPort;
            int sourcePort = tcpHeader.sourcePort;

//                Log.d("###","Src: "+referencePacket.ip4Header.sourceAddress +":"+ sourcePort
//                        + "\nDest: "+destinationAddress+":"+destinationPort);
            try
            {
                readBytes = inputChannel.read(receiveBuffer);
                if (referencePacket.ip4Header.sourceAddress.toString().equals("/52.88.216.252")&&sourcePort==80)
                {
                    tcb.totalByteTransferred+=readBytes;
                    Log.d("52server","byteCnt:"+tcb.totalByteTransferred);
                }
            }
            catch (Exception e)
            {
                Log.d(TAG, "Network read error: " + tcb.ipAndPort + " " + e.toString());

                //此处之前应该打断点，使网络恢复之后再进行下面的操作
                try {
                    SocketChannel reconnectChannel = SocketChannel.open();
                    reconnectChannel.configureBlocking(true);//设置为阻塞模式
                    localVPNService.protect(reconnectChannel.socket());
                    reconnectChannel.connect(new InetSocketAddress(referencePacket.ip4Header.sourceAddress, sourcePort));
                    OutputStream outputStream = reconnectChannel.socket().getOutputStream();
                    InputStream inputStream = reconnectChannel.socket().getInputStream();

                    String msg = "GET /wp-content/uploads/2015/09/DSC4495.jpg HTTP/1.1\r\n" +
                            "User-Agent: Dalvik/2.1.0 (Linux; U; Android 6.0.1; Nexus 6P Build/MMB29M)\r\n" +
                            "Host: 52.88.216.252\r\n" +
                            "Connection: close\r\n" +
                            "Accept-Encoding: gzip\r\n" +
                            "\r\n";
//                    String msg = "GET /json_test.txt HTTP/1.1\r\n" +
//                            "User-Agent: Dalvik/2.1.0 (Linux; U; Android 6.0.1; Nexus 6P Build/MMB29M)\r\n" +
//                            "Host: 52.88.216.252\r\n" +
//                            "Connection: close\r\n" +
//                            "Accept-Encoding: gzip\r\n" +
//                            "\r\n";

                    byte[] msgByte = msg.getBytes();
                    int rc;
                    byte[] buff = new byte[ByteBufferPool.BUFFER_SIZE];
                    outputStream.write(msgByte);
                    outputStream.flush();
//                    reconnectChannel.write(ByteBuffer.wrap(msgByte));

                    /**
                     * 以下操作：把之前传过的部分全部跳过
                     */
                    int reconnectTotalByteCnt = 0;
                    while (tcb.totalByteTransferred-reconnectTotalByteCnt>ByteBufferPool.BUFFER_SIZE){
                        rc = inputStream.read(buff, 0, ByteBufferPool.BUFFER_SIZE);
                        reconnectTotalByteCnt += rc;
                    }
                    while (reconnectTotalByteCnt<tcb.totalByteTransferred) {
                        rc = inputStream.read(buff, 0, tcb.totalByteTransferred-reconnectTotalByteCnt);
                        reconnectTotalByteCnt += rc;
                    }
                    /**
                     * 现在，跟断点位置对齐，即tcb.totalByteTransferred。可以从断点位置开始继续传输了
                     */
                    while ((rc = reconnectChannel.read(receiveBuffer))!=-1) {
                        referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                                tcb.mySequenceNum, tcb.myAcknowledgementNum, rc);
                        tcb.mySequenceNum += rc; // Next sequence number
                        receiveBuffer.position(HEADER_SIZE + rc);
                        outputQueue.offer(receiveBuffer);
                        receiveBuffer = ByteBufferPool.acquire();
                        receiveBuffer.position(HEADER_SIZE);
                    }
                    receiveBuffer.limit(HEADER_SIZE);
                    outputQueue.offer(receiveBuffer);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }

//                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);//给APP一个Reset信息
//                outputQueue.offer(receiveBuffer);
//                TCB.closeTCB(tcb);
                return;
            }

            if (readBytes == -1)
            {
                // End of stream, stop waiting until we push more data
                key.interestOps(0);
                tcb.waitingForNetworkData = false;

                if (tcb.status != TCBStatus.CLOSE_WAIT)
                {
                    ByteBufferPool.release(receiveBuffer);
                    return;
                }

                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.FIN, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            }
            else
            {
                // XXX: We should ideally be splitting segments by MTU/MSS, but this seems to work without
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes);
                tcb.mySequenceNum += readBytes; // Next sequence number
                receiveBuffer.position(HEADER_SIZE + readBytes);
            }
        }
        outputQueue.offer(receiveBuffer);
    }
}
