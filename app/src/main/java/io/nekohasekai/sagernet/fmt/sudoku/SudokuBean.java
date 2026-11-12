package io.nekohasekai.sagernet.fmt.sudoku;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * Kombain: Sudoku (github.com/SUDOKU-ASCII/sudoku) — traffic encoded as 4x4 Sudoku puzzles,
 * low-entropy / printable. Runs as a separate process (libsudoku.so), like the Xray engine.
 */
public class SudokuBean extends AbstractBean {

    /** client private key (hex) */
    public String key;
    /** chacha20-poly1305 | aes-128-gcm | none */
    public String aead;
    /** prefer_entropy | prefer_ascii | up_ascii_down_entropy | up_entropy_down_ascii */
    public String ascii;
    public Integer paddingMin;
    public Integer paddingMax;
    /** true = classic Sudoku downlink, false = bandwidth-optimized packed downlink */
    public Boolean pureDownlink;
    /** custom byte layouts, comma separated ("xpxvvpvv") */
    public String customTables;
    /** HTTP masking: "" (off) | legacy | stream | poll | auto | ws */
    public String httpMask;
    public Boolean httpMaskTls;
    public String httpMaskHost;
    public String httpMaskPath;
    /** off | auto | on */
    public String multiplex;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (key == null) key = "";
        if (aead == null) aead = "chacha20-poly1305";
        if (ascii == null) ascii = "prefer_entropy";
        if (paddingMin == null) paddingMin = 5;
        if (paddingMax == null) paddingMax = 15;
        if (pureDownlink == null) pureDownlink = true;
        if (customTables == null) customTables = "";
        if (httpMask == null) httpMask = "ws";
        if (httpMaskTls == null) httpMaskTls = false;
        if (httpMaskHost == null) httpMaskHost = "";
        if (httpMaskPath == null) httpMaskPath = "";
        if (multiplex == null) multiplex = "off";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(key);
        output.writeString(aead);
        output.writeString(ascii);
        output.writeInt(paddingMin);
        output.writeInt(paddingMax);
        output.writeBoolean(pureDownlink);
        output.writeString(customTables);
        output.writeString(httpMask);
        output.writeBoolean(httpMaskTls);
        output.writeString(httpMaskHost);
        output.writeString(httpMaskPath);
        output.writeString(multiplex);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        key = input.readString();
        aead = input.readString();
        ascii = input.readString();
        paddingMin = input.readInt();
        paddingMax = input.readInt();
        pureDownlink = input.readBoolean();
        customTables = input.readString();
        httpMask = input.readString();
        httpMaskTls = input.readBoolean();
        httpMaskHost = input.readString();
        httpMaskPath = input.readString();
        multiplex = input.readString();
    }

    @NotNull
    @Override
    public SudokuBean clone() {
        return KryoConverters.deserialize(new SudokuBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SudokuBean> CREATOR = new CREATOR<SudokuBean>() {
        @NonNull
        @Override
        public SudokuBean newInstance() {
            return new SudokuBean();
        }

        @Override
        public SudokuBean[] newArray(int size) {
            return new SudokuBean[size];
        }
    };
}
