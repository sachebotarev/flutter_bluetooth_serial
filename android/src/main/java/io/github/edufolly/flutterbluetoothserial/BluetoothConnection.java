package io.github.edufolly.flutterbluetoothserial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Arrays;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

/// Universal Bluetooth serial connection class (for Java)
public abstract class BluetoothConnection {
    protected static final UUID DEFAULT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    protected BluetoothAdapter bluetoothAdapter;

    protected ConnectionThread connectionThread = null;

    protected ServerThread serverThread = null;

    public boolean isConnected() {
        return connectionThread != null && connectionThread.requestedClosing != true;
    }

    public boolean isListening() {
        return serverThread != null && serverThread.requestedKilling != true;
    }

    public BluetoothConnection(BluetoothAdapter bluetoothAdapter) {
        this.bluetoothAdapter = bluetoothAdapter;
    }


    // @TODO . `connect` could be done perfored on the other thread
    // @TODO . `connect` parameter: timeout
    // @TODO . `connect` other methods than `createRfcommSocketToServiceRecord`, including hidden one raw `createRfcommSocket` (on channel).
    // @TODO ? how about turning it into factoried?
    /// Connects to given device by hardware address
    public void connect(String address, UUID uuid) throws IOException {
        if (isConnected()) {
            throw new IOException("already connected");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            throw new IOException("device not found");
        }

        BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(uuid); // @TODO . introduce ConnectionMethod
        if (socket == null) {
            throw new IOException("socket connection not established");
        }

        // Cancel discovery, even though we didn't start it
        bluetoothAdapter.cancelDiscovery();

        socket.connect();

        connectionThread = new ConnectionThread(socket);
        connectionThread.start();
    }

    /// Connects to given device by hardware address (default UUID used)
    public void connect(String address) throws IOException {
        connect(address, DEFAULT_UUID);
    }

    /// Disconnects current session (ignore if not connected)
    public void disconnect() {
        if (isConnected()) {
            connectionThread.cancel();
            connectionThread = null;
        }
        stopListening();
    }

    public void listen(String name) throws IOException {
        serverThread = new ServerThread(name, DEFAULT_UUID);
        serverThread.start();
    }

    public void stopListening() {
        if (isListening()) {
            serverThread.cancel();
            serverThread = null;
        }
    }

    /// Writes to connected remote device 
    public void write(byte[] data) throws IOException {
        if (!isConnected()) {
            throw new IOException("not connected");
        }

        connectionThread.write(data);
    }

    /// Callback for reading data.
    protected abstract void onRead(byte[] data);

    /// Callback for disconnection.
    protected abstract void onDisconnected(boolean byRemote);

    /// Callback for accepting connection.
    protected abstract void onConnAccepted(boolean byRemote);

    /// Thread to handle connection I/O
    private class ConnectionThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private boolean requestedClosing = false;

        ConnectionThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            this.input = tmpIn;
            this.output = tmpOut;
        }

        /// Thread main code
        public void run() {
            byte[] buffer = new byte[1024];
            byte[] result;
            int bytes;
            int lastRead;

            while (!requestedClosing) {
                try {
                    do {
                        lastRead = 0;
                        lastRead = input.read(buffer);
                        if (result == null) {
                            result = Arrays.copyOf(buffer, lastRead);
                        } else {
                            result = Arrays.copyOf(result, bytes + lastRead);
                            System.arraycopy(buffer, 0, result, bytes, lastRead);
                        }
                        bytes += lastRead;
                    } while (lastRead > 0);
                    onRead(result);
                    result = null;
                    bytes = 0;
                } catch (IOException e) {
                    // `input.read` throws when closed by remote device
                    break;
                }
            }

            // Make sure output stream is closed
            if (output != null) {
                try {
                    output.close();
                } catch (Exception e) {
                }
            }

            // Make sure input stream is closed
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                }
            }

            // Callback on disconnected, with information which side is closing
            onDisconnected(!requestedClosing);

            // Just prevent unnecessary `cancel`ing
            requestedClosing = true;
        }

        /// Writes to output stream
        public void write(byte[] bytes) {
            try {
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /// Stops the thread, disconnects
        public void cancel() {
            if (requestedClosing) {
                return;
            }
            requestedClosing = true;

            // Flush output buffers befoce closing
            try {
                output.flush();
            } catch (Exception e) {
            }

            // Close the connection socket
            if (socket != null) {
                try {
                    // Might be useful (see https://stackoverflow.com/a/22769260/4880243)
                    Thread.sleep(111);

                    socket.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private class ServerThread extends Thread {
        private boolean requestedKilling = false;
        private final BluetoothServerSocket serverSocket;

        ServerThread(String name, UUID uuid) throws IOException {
            disconnect();
            serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid);
        }

        public void run() {
            BluetoothSocket socket = null;

            while (socket == null && !requestedKilling) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    onConnAccepted(false);
                }

                if (socket != null) {
                    connectionThread = new ConnectionThread(socket);
                    connectionThread.start();
                    onConnAccepted(true);
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            connectionThread.write(bytes);
        }

        public void cancel() {
            requestedKilling = true;
            try {
                disconnect();
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
