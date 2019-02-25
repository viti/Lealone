/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.security.SHA256;
import org.lealone.common.util.DataUtils;
import org.lealone.common.util.IOUtils;
import org.lealone.common.util.MathUtils;
import org.lealone.common.util.StringUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.DataBuffer;
import org.lealone.db.Session;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.result.SimpleResultSet;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueArray;
import org.lealone.db.value.ValueBoolean;
import org.lealone.db.value.ValueByte;
import org.lealone.db.value.ValueBytes;
import org.lealone.db.value.ValueDate;
import org.lealone.db.value.ValueDecimal;
import org.lealone.db.value.ValueDouble;
import org.lealone.db.value.ValueFloat;
import org.lealone.db.value.ValueInt;
import org.lealone.db.value.ValueJavaObject;
import org.lealone.db.value.ValueLob;
import org.lealone.db.value.ValueLong;
import org.lealone.db.value.ValueNull;
import org.lealone.db.value.ValueResultSet;
import org.lealone.db.value.ValueShort;
import org.lealone.db.value.ValueString;
import org.lealone.db.value.ValueStringFixed;
import org.lealone.db.value.ValueStringIgnoreCase;
import org.lealone.db.value.ValueTime;
import org.lealone.db.value.ValueTimestamp;
import org.lealone.db.value.ValueUuid;
import org.lealone.storage.PageKey;

/**
 * The transfer class is used to send and receive Value objects.
 * It is used on both the client side, and on the server side.
 * 
 * @author H2 Group
 * @author zhh
 */
public class Transfer implements NetSerializer {

    public static final int BUFFER_SIZE = 4 * 1024;
    private static final int LOB_MAGIC = 0x1234;
    private static final int LOB_MAC_SALT_LENGTH = 16;

    static final byte REQUEST = 1;
    private static final byte RESPONSE = 2;

    private TransferConnection conn;
    private WritableChannel writableChannel;
    private Session session;
    private DataInputStream in;
    private final DataOutputStream out;
    private final ResettableBufferOutputStream resettableOutputStream;

    public Transfer(TransferConnection conn, WritableChannel writableChannel) {
        this.conn = conn;
        this.writableChannel = writableChannel;

        resettableOutputStream = new ResettableBufferOutputStream(writableChannel, BUFFER_SIZE);
        out = new DataOutputStream(resettableOutputStream);
    }

    public Transfer(TransferConnection conn, WritableChannel writableChannel, Session session) {
        this(conn, writableChannel);
        this.session = session;
    }

    public Transfer(TransferConnection conn, WritableChannel writableChannel, NetBuffer inBuffer) {
        this(conn, writableChannel);
        if (inBuffer != null) {
            in = new DataInputStream(new NetBufferInputStream(inBuffer));
        }
    }

    public int getDataOutputStreamSize() {
        return out.size();
    }

    public void setPayloadSize(int payloadStartPos, int size) {
        resettableOutputStream.setPayloadSize(payloadStartPos, size);
    }

    public Transfer copy(Session session) {
        return new Transfer(conn, writableChannel, session);
    }

    public TransferConnection getTransferConnection() {
        return conn;
    }

    public void addAsyncCallback(int id, AsyncCallback<?> ac) {
        ac.setTransfer(this);
        conn.addAsyncCallback(id, ac);
    }

    public Transfer writeRequestHeader(int packetType) throws IOException {
        checkSession();
        int id = session.getNextId();
        return writeRequestHeader(id, packetType);
    }

    public Transfer writeRequestHeader(int id, int packetType) throws IOException {
        checkSession();
        writeByte(REQUEST).writeInt(id).writeInt(packetType).writeInt(session.getSessionId());
        return this;
    }

    public Transfer writeRequestHeaderWithoutSessionId(int id, int packetType) throws IOException {
        writeByte(REQUEST).writeInt(id).writeInt(packetType);
        return this;
    }

    private void checkSession() {
        if (session == null) {
            throw DbException.throwInternalError("session is null");
        }
    }

    public Transfer writeResponseHeader(int id, int status) throws IOException {
        writeByte(RESPONSE).writeInt(id).writeInt(status);
        return this;
    }

    public void setDataInputStream(DataInputStream in) {
        this.in = in;
    }

    public DataInputStream getDataInputStream() {
        return in;
    }

    public DataOutputStream getDataOutputStream() {
        return out;
    }

    /**
     * 当输出流写到一半时碰到某种异常了(可能是内部代码实现bug)，比如产生了NPE，
     * 就会转到错误处理，生成一个新的错误协议包，但是前面产生的不完整的内容没有正常结束，
     * 这会导致客户端无法正常解析数据，所以这里允许在生成错误协议包之前清除之前的内容，
     * 如果之前的协议包不完整，但是已经发出去一半了，这里的方案也无能为力。 
     */
    public void reset() throws IOException {
        resettableOutputStream.reset();
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Session getSession() {
        return session;
    }

    public void setSSL(boolean ssl) {
        // this.ssl = ssl;
    }

    public void setVersion(int version) { // TODO 以后协议修改了再使用版本号区分
    }

    /**
     * Write pending changes.
     */
    public void flush() throws IOException {
        resettableOutputStream.flush();
    }

    /**
     * Close the transfer object.
     */
    public void close() {
        if (writableChannel != null) {
            try {
                if (out.size() > 4)
                    flush();
            } catch (IOException e) {
                DbException.traceThrowable(e);
            } finally {
                conn = null;
                session = null;
                writableChannel = null;
            }
        }
    }

    public boolean isClosed() {
        return writableChannel == null;
    }

    /**
     * Write a boolean.
     *
     * @param x the value
     * @return itself
     */
    public Transfer writeBoolean(boolean x) throws IOException {
        out.writeByte((byte) (x ? 1 : 0));
        return this;
    }

    /**
     * Read a boolean.
     *
     * @return the value
     */
    public boolean readBoolean() throws IOException {
        return in.readByte() == 1;
    }

    /**
     * Write a byte.
     *
     * @param x the value
     * @return itself
     */
    private Transfer writeByte(byte x) throws IOException {
        out.writeByte(x);
        return this;
    }

    /**
     * Read a byte.
     *
     * @return the value
     */
    public byte readByte() throws IOException {
        return in.readByte();
    }

    /**
     * Write an int.
     *
     * @param x the value
     * @return itself
     */
    @Override
    public Transfer writeInt(int x) throws IOException {
        out.writeInt(x);
        return this;
    }

    /**
     * Read an int.
     *
     * @return the value
     */
    @Override
    public int readInt() throws IOException {
        return in.readInt();
    }

    /**
     * Write a long.
     *
     * @param x the value
     * @return itself
     */
    public Transfer writeLong(long x) throws IOException {
        out.writeLong(x);
        return this;
    }

    /**
     * Read a long.
     *
     * @return the value
     */
    public long readLong() throws IOException {
        return in.readLong();
    }

    /**
     * Write a double.
     *
     * @param i the value
     * @return itself
     */
    private Transfer writeDouble(double i) throws IOException {
        out.writeDouble(i);
        return this;
    }

    /**
     * Read a double.
     *
     * @return the value
     */
    private double readDouble() throws IOException {
        return in.readDouble();
    }

    /**
     * Write a float.
     *
     * @param i the value
     * @return itself
     */
    private Transfer writeFloat(float i) throws IOException {
        out.writeFloat(i);
        return this;
    }

    /**
     * Read a float.
     *
     * @return the value
     */
    private float readFloat() throws IOException {
        return in.readFloat();
    }

    /**
     * Write a string. The maximum string length is Integer.MAX_VALUE.
     *
     * @param s the value
     * @return itself
     */
    @Override
    public Transfer writeString(String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
        } else {
            int len = s.length();
            out.writeInt(len);
            for (int i = 0; i < len; i++) {
                out.writeChar(s.charAt(i));
            }
        }
        return this;
    }

    /**
     * Read a string.
     *
     * @return the value
     */
    @Override
    public String readString() throws IOException {
        int len = in.readInt();
        if (len == -1) {
            return null;
        }
        StringBuilder buff = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            buff.append(in.readChar());
        }
        String s = buff.toString();
        s = StringUtils.cache(s);
        return s;
    }

    /**
     * Write a byte buffer.
     *
     * @param data the value
     * @return itself
     */
    @Override
    public Transfer writeByteBuffer(ByteBuffer data) throws IOException {
        if (data == null) {
            writeInt(-1);
        } else {
            if (data.hasArray()) {
                writeBytes(data.array(), data.arrayOffset(), data.limit());
            } else {
                byte[] bytes = new byte[data.limit()];
                data.get(bytes);
                writeBytes(bytes);
            }
        }
        return this;
    }

    /**
     * Write a byte array.
     *
     * @param data the value
     * @return itself
     */
    public Transfer writeBytes(byte[] data) throws IOException {
        if (data == null) {
            writeInt(-1);
        } else {
            writeInt(data.length);
            out.write(data);
        }
        return this;
    }

    /**
     * Write a number of bytes.
     *
     * @param buff the value
     * @param off the offset
     * @param len the length
     * @return itself
     */
    public Transfer writeBytes(byte[] buff, int off, int len) throws IOException {
        writeInt(len);
        out.write(buff, off, len);
        return this;
    }

    /**
     * Read a byte buffer.
     *
     * @return the value
     */
    @Override
    public ByteBuffer readByteBuffer() throws IOException {
        byte[] b = readBytes();
        if (b == null)
            return null;
        else
            return ByteBuffer.wrap(b);
    }

    /**
     * Read a byte array.
     *
     * @return the value
     */
    public byte[] readBytes() throws IOException {
        int len = readInt();
        if (len == -1) {
            return null;
        }
        byte[] b = DataUtils.newBytes(len);
        in.readFully(b);
        return b;
    }

    /**
     * Read a number of bytes.
     *
     * @param buff the target buffer
     * @param off the offset
     * @param len the number of bytes to read
     */
    public void readBytes(byte[] buff, int off, int len) throws IOException {
        in.readFully(buff, off, len);
    }

    @Override
    public Transfer writePageKey(PageKey pk) throws IOException {
        writeValue((Value) pk.key);
        writeBoolean(pk.first);
        return this;
    }

    @Override
    public PageKey readPageKey() throws IOException {
        Object value = readValue();
        boolean first = readBoolean();
        return new PageKey(value, first);
    }

    /**
     * Write a value.
     *
     * @param v the value
     */
    public void writeValue(Value v) throws IOException {
        int type = v.getType();
        writeInt(type);
        switch (type) {
        case Value.NULL:
            break;
        case Value.BYTES:
        case Value.JAVA_OBJECT:
            writeBytes(v.getBytesNoCopy());
            break;
        case Value.UUID: {
            ValueUuid uuid = (ValueUuid) v;
            writeLong(uuid.getHigh());
            writeLong(uuid.getLow());
            break;
        }
        case Value.BOOLEAN:
            writeBoolean(v.getBoolean().booleanValue());
            break;
        case Value.BYTE:
            writeByte(v.getByte());
            break;
        case Value.DATE:
            writeLong(((ValueDate) v).getDateValue());
            break;
        case Value.TIME:
            writeLong(((ValueTime) v).getNanos());
            break;
        case Value.TIMESTAMP: {
            ValueTimestamp ts = (ValueTimestamp) v;
            writeLong(ts.getDateValue());
            writeLong(ts.getNanos());
            break;
        }
        case Value.DECIMAL:
            writeString(v.getString());
            break;
        case Value.DOUBLE:
            writeDouble(v.getDouble());
            break;
        case Value.FLOAT:
            writeFloat(v.getFloat());
            break;
        case Value.SHORT:
            writeInt(v.getShort());
            break;
        case Value.INT:
            writeInt(v.getInt());
            break;
        case Value.LONG:
            writeLong(v.getLong());
            break;
        case Value.STRING:
        case Value.STRING_IGNORECASE:
        case Value.STRING_FIXED:
            writeString(v.getString());
            break;
        case Value.BLOB:
        case Value.CLOB: {
            if (v instanceof ValueLob) {
                ValueLob lob = (ValueLob) v;
                if (lob.isStored()) {
                    writeLong(-1);
                    writeInt(lob.getTableId());
                    writeLong(lob.getLobId());
                    writeBytes(calculateLobMac(lob.getLobId()));
                    writeLong(lob.getPrecision());
                    break;
                }
            }
            long length = v.getPrecision();
            if (length < 0) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "length=" + length);
            }
            writeLong(length);
            if (type == Value.BLOB) {
                long written = IOUtils.copyAndCloseInput(v.getInputStream(), out);
                if (written != length) {
                    throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "length:" + length + " written:" + written);
                }
            } else {
                Reader reader = v.getReader();
                DataBuffer.copyString(reader, out);
            }
            writeInt(LOB_MAGIC);
            break;
        }
        case Value.ARRAY: {
            ValueArray va = (ValueArray) v;
            Value[] list = va.getList();
            int len = list.length;
            Class<?> componentType = va.getComponentType();
            if (componentType == Object.class) {
                writeInt(len);
            } else {
                writeInt(-(len + 1));
                writeString(componentType.getName());
            }
            for (Value value : list) {
                writeValue(value);
            }
            break;
        }
        case Value.RESULT_SET: {
            try {
                ResultSet rs = ((ValueResultSet) v).getResultSet();
                rs.beforeFirst();
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                writeInt(columnCount);
                for (int i = 0; i < columnCount; i++) {
                    writeString(meta.getColumnName(i + 1));
                    writeInt(meta.getColumnType(i + 1));
                    writeInt(meta.getPrecision(i + 1));
                    writeInt(meta.getScale(i + 1));
                }
                while (rs.next()) {
                    writeBoolean(true);
                    for (int i = 0; i < columnCount; i++) {
                        int t = DataType.convertSQLTypeToValueType(meta.getColumnType(i + 1));
                        Value val = DataType.readValue(session, rs, i + 1, t);
                        writeValue(val);
                    }
                }
                writeBoolean(false);
                rs.beforeFirst();
            } catch (SQLException e) {
                throw DbException.convertToIOException(e);
            }
            break;
        }
        default:
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "type=" + type);
        }
    }

    /**
     * Read a value.
     *
     * @return the value
     */
    public Value readValue() throws IOException {
        int type = readInt();
        switch (type) {
        case Value.NULL:
            return ValueNull.INSTANCE;
        case Value.BYTES:
            return ValueBytes.getNoCopy(readBytes());
        case Value.JAVA_OBJECT:
            return ValueJavaObject.getNoCopy(null, readBytes());
        case Value.UUID:
            return ValueUuid.get(readLong(), readLong());
        case Value.BOOLEAN:
            return ValueBoolean.get(readBoolean());
        case Value.BYTE:
            return ValueByte.get(readByte());
        case Value.DATE:
            return ValueDate.fromDateValue(readLong());
        case Value.TIME:
            return ValueTime.fromNanos(readLong());
        case Value.TIMESTAMP:
            return ValueTimestamp.fromDateValueAndNanos(readLong(), readLong());
        case Value.DECIMAL:
            return ValueDecimal.get(new BigDecimal(readString()));
        case Value.DOUBLE:
            return ValueDouble.get(readDouble());
        case Value.FLOAT:
            return ValueFloat.get(readFloat());
        case Value.SHORT:
            return ValueShort.get((short) readInt());
        case Value.INT:
            return ValueInt.get(readInt());
        case Value.LONG:
            return ValueLong.get(readLong());
        case Value.STRING:
            return ValueString.get(readString());
        case Value.STRING_IGNORECASE:
            return ValueStringIgnoreCase.get(readString());
        case Value.STRING_FIXED:
            return ValueStringFixed.get(readString());
        case Value.BLOB:
        case Value.CLOB: {
            long length = readLong();
            if (length == -1) {
                int tableId = readInt();
                long id = readLong();
                byte[] hmac = readBytes();
                long precision = readLong();
                return ValueLob.create(type, session.getDataHandler(), tableId, id, hmac, precision);
            }
            byte[] small;
            int len = (int) length;
            if (type == Value.BLOB) {
                small = new byte[len];
                IOUtils.readFully(in, small, len);
            } else {
                DataReader reader = new DataReader(in);
                char[] buff = new char[len];
                IOUtils.readFully(reader, buff, len);
                small = new String(buff).getBytes("UTF-8");
            }
            int magic = readInt();
            if (magic != LOB_MAGIC) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "magic=" + magic);
            }
            return ValueLob.createSmallLob(type, small, length);
        }
        case Value.ARRAY: {
            int len = readInt();
            Class<?> componentType = Object.class;
            if (len < 0) {
                len = -(len + 1);
                componentType = Utils.loadUserClass(readString());
            }
            Value[] list = new Value[len];
            for (int i = 0; i < len; i++) {
                list[i] = readValue();
            }
            return ValueArray.get(componentType, list);
        }
        case Value.RESULT_SET: {
            SimpleResultSet rs = new SimpleResultSet();
            int columns = readInt();
            for (int i = 0; i < columns; i++) {
                rs.addColumn(readString(), readInt(), readInt(), readInt());
            }
            while (true) {
                if (!readBoolean()) {
                    break;
                }
                Object[] o = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    o[i] = readValue().getObject();
                }
                rs.addRow(o);
            }
            return ValueResultSet.get(rs);
        }
        default:
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "type=" + type);
        }
    }

    /**
     * Verify the HMAC.
     *
     * @param hmac the message authentication code
     * @param lobId the lobId
     * @throws DbException if the HMAC does not match
     */
    public void verifyLobMac(byte[] hmac, long lobId) {
        byte[] result = calculateLobMac(lobId);
        if (!Utils.compareSecure(hmac, result)) {
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1,
                    "Invalid lob hmac; possibly the connection was re-opened internally");
        }
    }

    private byte[] calculateLobMac(long lobId) {
        byte[] lobMacSalt = null;
        if (session != null) {
            lobMacSalt = session.getLobMacSalt();
        }
        if (lobMacSalt == null) {
            lobMacSalt = MathUtils.secureRandomBytes(LOB_MAC_SALT_LENGTH);
            if (session != null) {
                session.setLobMacSalt(lobMacSalt);
            }
        }
        byte[] data = new byte[8];
        Utils.writeLong(data, 0, lobId);
        byte[] hmacData = SHA256.getHashWithSalt(data, lobMacSalt);
        return hmacData;
    }

    /**
     * This class is backed by an input stream and supports reading values and
     * variable size data.
     */
    private static class DataReader extends Reader {

        private final InputStream in;

        /**
         * Create a new data reader.
         *
         * @param in the input stream
         */
        public DataReader(InputStream in) {
            this.in = in;
        }

        /**
         * Read a byte.
         *
         * @return the byte
         */
        private byte readByte() throws IOException {
            int x = in.read();
            if (x < 0) {
                throw new FastEOFException();
            }
            return (byte) x;
        }

        /**
         * Read one character from the input stream.
         *
         * @return the character
         */
        private char readChar() throws IOException {
            int x = readByte() & 0xff;
            if (x < 0x80) {
                return (char) x;
            } else if (x >= 0xe0) {
                return (char) (((x & 0xf) << 12) + ((readByte() & 0x3f) << 6) + (readByte() & 0x3f));
            } else {
                return (char) (((x & 0x1f) << 6) + (readByte() & 0x3f));
            }
        }

        @Override
        public void close() throws IOException {
            // ignore
        }

        @Override
        public int read(char[] buff, int off, int len) throws IOException {
            int i = 0;
            try {
                for (; i < len; i++) {
                    buff[i] = readChar();
                }
                return len;
            } catch (EOFException e) {
                return i;
            }
        }
    }

    /**
     * Constructing such an EOF exception is fast, because the stack trace is
     * not filled in. If used in a static context, this will also avoid
     * classloader memory leaks.
     */
    private static class FastEOFException extends EOFException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return null;
        }
    }

    private static class ResettableBufferOutputStream extends NetBufferOutputStream {

        ResettableBufferOutputStream(WritableChannel writableChannel, int initialSizeHint) {
            super(writableChannel, initialSizeHint);
        }

        @Override
        public void flush() throws IOException {
            writePacketLength();
            super.flush();
        }

        @Override
        protected void reset() {
            super.reset();
            buffer.appendInt(0); // write packet header for next
        }

        private void writePacketLength() {
            int v = buffer.length() - 4;
            buffer.setByte(0, (byte) ((v >>> 24) & 0xFF));
            buffer.setByte(1, (byte) ((v >>> 16) & 0xFF));
            buffer.setByte(2, (byte) ((v >>> 8) & 0xFF));
            buffer.setByte(3, (byte) (v & 0xFF));
        }

        public void setPayloadSize(int payloadStartPos, int size) {
            payloadStartPos += 4;
            int v = size;
            buffer.setByte(payloadStartPos, (byte) ((v >>> 24) & 0xFF));
            buffer.setByte(payloadStartPos + 1, (byte) ((v >>> 16) & 0xFF));
            buffer.setByte(payloadStartPos + 2, (byte) ((v >>> 8) & 0xFF));
            buffer.setByte(payloadStartPos + 3, (byte) (v & 0xFF));
        }
    }
}
