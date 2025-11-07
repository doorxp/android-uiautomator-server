package com.github.uiautomator;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnRunnable implements Runnable {
    private static final String TAG = "VpnRunnable";
    private static final int PACKET_SIZE = 32767;

    private ParcelFileDescriptor vpnInterface;
    private FileInputStream in;
    private FileOutputStream out;
    private ConcurrentHashMap<String, TcpConnection> tcpConnections;
    private ExecutorService executorService;
    private boolean isRunning = true;

    public VpnRunnable(ParcelFileDescriptor vpnInterface) {
        this.vpnInterface = vpnInterface;
        this.tcpConnections = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool();
    }

    @Override
    public void run() {
        try {
            // 获取 VPN 接口的输入输出流
            in = new FileInputStream(vpnInterface.getFileDescriptor());
            out = new FileOutputStream(vpnInterface.getFileDescriptor());

            // 创建数据包缓冲区
            ByteBuffer packet = ByteBuffer.allocate(PACKET_SIZE);

            // 处理数据包
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                // 读取数据包
                packet.clear();
                int length = in.read(packet.array());

                if (length > 0) {
                    packet.limit(length);
                    handlePacket(packet);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN processing error", e);
        } finally {
            cleanup();
        }
    }

    private void handlePacket(ByteBuffer packet) {
        try {
            // 获取IP版本
            byte versionAndIHL = packet.get(0);
            int version = (versionAndIHL >> 4) & 0x0F;

            if (version == 4) {
                handleIPv4Packet(packet);
            } else if (version == 6) {
                handleIPv6Packet(packet);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling packet", e);
        }
    }

    private void handleIPv4Packet(ByteBuffer packet) throws Exception {
        // 复位position
        packet.position(0);

        // 解析IPv4头部
        byte versionAndIHL = packet.get();
        int headerLength = (versionAndIHL & 0x0F) * 4;

        // 跳过TOS
        packet.get();

        // 获取总长度
        short totalLength = packet.getShort();

        // 跳过ID、标志和分片偏移
        packet.position(packet.position() + 4);

        // TTL
        byte ttl = packet.get();

        // 协议
        byte protocol = packet.get();

        // 跳过校验和
        packet.position(packet.position() + 2);

        // 源IP地址
        byte[] srcAddr = new byte[4];
        packet.get(srcAddr);
        InetAddress srcAddress = InetAddress.getByAddress(srcAddr);

        // 目标IP地址
        byte[] dstAddr = new byte[4];
        packet.get(dstAddr);
        InetAddress dstAddress = InetAddress.getByAddress(dstAddr);

        // 处理TCP协议 (协议号6)
        if (protocol == 6) {
            packet.position(headerLength);
            ByteBuffer tcpData = packet.slice();
            handleTcpPacket(srcAddress, dstAddress, tcpData, totalLength - headerLength);
        }
    }

    private void handleIPv6Packet(ByteBuffer packet) {
        // IPv6处理逻辑（如需要可以实现）
        Log.d(TAG, "IPv6 packet received, not implemented yet");
    }

    private void handleTcpPacket(InetAddress srcAddr, InetAddress dstAddr,
                                 ByteBuffer tcpData, int tcpLength) throws Exception {
        // 解析TCP头部
        int srcPort = tcpData.getShort() & 0xFFFF;
        int dstPort = tcpData.getShort() & 0xFFFF;

        // 序列号
        int seqNum = tcpData.getInt();

        // 确认号
        int ackNum = tcpData.getInt();

        // 数据偏移和标志
        byte dataOffsetAndReserved = tcpData.get();
        int tcpHeaderLength = ((dataOffsetAndReserved >> 4) & 0x0F) * 4;

        byte flags = tcpData.get();
        boolean syn = (flags & 0x02) != 0;
        boolean ack = (flags & 0x10) != 0;
        boolean fin = (flags & 0x01) != 0;
        boolean rst = (flags & 0x04) != 0;

        // 窗口大小
        short window = tcpData.getShort();

        // 对于HTTPS (端口443) 进行透传
        if (dstPort == 443) {
            handleHttpsConnection(srcAddr, srcPort, dstAddr, dstPort,
                    tcpData, tcpHeaderLength, tcpLength);
        }
    }

    private void handleHttpsConnection(InetAddress srcAddr, int srcPort,
                                       InetAddress dstAddr, int dstPort,
                                       ByteBuffer tcpData, int tcpHeaderLength,
                                       int tcpLength) {
        String connectionKey = String.format("%s:%d-%s:%d",
                srcAddr.getHostAddress(), srcPort,
                dstAddr.getHostAddress(), dstPort);

        // 获取或创建TCP连接
        TcpConnection connection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connection = tcpConnections.computeIfAbsent(connectionKey,
                    k -> new TcpConnection(srcAddr, srcPort, dstAddr, dstPort));
        } else {
            connection = null;
        }

        // 获取TCP数据部分
        tcpData.position(tcpHeaderLength);
        int dataLength = tcpLength - tcpHeaderLength;

        if (connection != null && dataLength > 0) {
            byte[] data = new byte[dataLength];
            tcpData.get(data);

            // 异步处理数据转发
            executorService.execute(() -> {
                try {
                    connection.forwardData(data);
                } catch (IOException e) {
                    Log.e(TAG, "Error forwarding HTTPS data", e);
                }
            });
        }
    }

    // TCP连接管理类
    private class TcpConnection {
        private InetAddress srcAddr;
        private int srcPort;
        private InetAddress dstAddr;
        private int dstPort;
        private SocketChannel channel;
        private boolean isConnected = false;

        public TcpConnection(InetAddress srcAddr, int srcPort,
                             InetAddress dstAddr, int dstPort) {
            this.srcAddr = srcAddr;
            this.srcPort = srcPort;
            this.dstAddr = dstAddr;
            this.dstPort = dstPort;
        }

        public synchronized void forwardData(byte[] data) throws IOException {
            if (!isConnected) {
                connect();
            }

            if (channel != null && channel.isConnected()) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                channel.write(buffer);

                // 读取响应数据
                ByteBuffer responseBuffer = ByteBuffer.allocate(PACKET_SIZE);
                int bytesRead = channel.read(responseBuffer);

                if (bytesRead > 0) {
                    responseBuffer.flip();
                    // 构建响应IP包并写回VPN接口
                    sendResponseToVpn(responseBuffer);
                }
            }
        }

        private void connect() throws IOException {
            try {
                channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(dstAddr, dstPort));

                // 等待连接完成
                while (!channel.finishConnect()) {
                    Thread.sleep(10);
                }

                isConnected = true;

                // 启动读取线程
                executorService.execute(new ResponseReader());

            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to " + dstAddr + ":" + dstPort, e);
                throw new IOException(e);
            }
        }

        private void sendResponseToVpn(ByteBuffer data) {
            try {
                // 构建IP包
                ByteBuffer ipPacket = buildIpPacket(dstAddr, srcAddr, srcPort, data);

                // 写入VPN接口
                synchronized (out) {
                    out.write(ipPacket.array(), 0, ipPacket.limit());
                    out.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending response to VPN", e);
            }
        }

        // 响应读取器
        private class ResponseReader implements Runnable {
            @Override
            public void run() {
                ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);

                try {
                    while (isConnected && channel.isConnected()) {
                        buffer.clear();
                        int bytesRead = channel.read(buffer);

                        if (bytesRead > 0) {
                            buffer.flip();
                            sendResponseToVpn(buffer);
                        } else if (bytesRead < 0) {
                            // 连接关闭
                            close();
                            break;
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading response", e);
                    close();
                }
            }
        }

        public void close() {
            isConnected = false;
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing channel", e);
                }
            }
            tcpConnections.remove(getConnectionKey());
        }

        private String getConnectionKey() {
            return String.format("%s:%d-%s:%d",
                    srcAddr.getHostAddress(), srcPort,
                    dstAddr.getHostAddress(), dstPort);
        }
    }

    private ByteBuffer buildIpPacket(InetAddress src, InetAddress dst, int srcPort, ByteBuffer data) {
        // 简化的IP包构建（实际需要完整实现）
        ByteBuffer packet = ByteBuffer.allocate(data.remaining() + 40);

        // IPv4头部
        packet.put((byte) 0x45); // 版本和头部长度
        packet.put((byte) 0x00); // TOS
        packet.putShort((short) (data.remaining() + 40)); // 总长度
        packet.putShort((short) 0); // ID
        packet.putShort((short) 0); // 标志和分片偏移
        packet.put((byte) 64); // TTL
        packet.put((byte) 6); // TCP协议
        packet.putShort((short) 0); // 校验和（稍后计算）

        // 源IP和目标IP
        packet.put(src.getAddress());
        packet.put(dst.getAddress());

        // TCP头部（简化版）
        packet.putShort((short) 443); // 源端口
        packet.putShort((short) (srcPort & 0xFFFF)); // 目标端口
        packet.putInt(0); // 序列号
        packet.putInt(0); // 确认号
        packet.put((byte) 0x50); // 头部长度
        packet.put((byte) 0x18); // 标志 (PSH + ACK)
        packet.putShort((short) 8192); // 窗口大小
        packet.putShort((short) 0); // 校验和
        packet.putShort((short) 0); // 紧急指针

        // 数据
        packet.put(data);

        packet.flip();
        return packet;
    }

    public void stop() {
        isRunning = false;
        executorService.shutdownNow();
        cleanup();
    }

    private void cleanup() {
        try {
            // 关闭所有连接
            for (TcpConnection connection : tcpConnections.values()) {
                connection.close();
            }
            tcpConnections.clear();

            if (in != null) in.close();
            if (out != null) out.close();
            if (vpnInterface != null) vpnInterface.close();

        } catch (IOException e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }
}
