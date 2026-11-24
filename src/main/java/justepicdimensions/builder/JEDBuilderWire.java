package justepicdimensions.builder;

import java.util.*;
import java.util.function.BiConsumer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.zip.*;
import justepicdimensions.JEDText;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Ordinary MAIN-thread NeoForge payloads. No connection or Netty pipeline changes. */
public final class JEDBuilderWire {
    public static final int CHUNK=8192,MAX_CHARS=32*1024*1024;
    private static final String COMPRESSED="JEDZ1:";
    // Enough encoded capacity for any bounded decoded UTF-8 message, including deflate overhead.
    public static final int MAX_WIRE_CHARS=((MAX_CHARS+65536+2)/3)*4+COMPRESSED.length();
    public static final int MAX_CHUNKS=(MAX_WIRE_CHARS+CHUNK-1)/CHUNK;
    public static final UUID OPEN=new UUID(0,0);
    public record Frame(UUID session,int request,int index,int count,String text) {
        public Frame {if(request<0||count<1||count>MAX_CHUNKS||index<0||index>=count||text.length()>CHUNK)throw new IllegalArgumentException("Invalid builder frame");}
    }
    private static final StreamCodec<RegistryFriendlyByteBuf,Frame> FRAMES=new StreamCodec<>() {
        public Frame decode(RegistryFriendlyByteBuf b){return new Frame(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(CHUNK));}
        public void encode(RegistryFriendlyByteBuf b,Frame v){b.writeUUID(v.session());b.writeVarInt(v.request());b.writeVarInt(v.index());b.writeVarInt(v.count());b.writeUtf(v.text(),CHUNK);}
    };
    public record Request(Frame frame) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:builder_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=FRAMES.map(Request::new,Request::frame);
        public Type<Request> type(){return TYPE;}
    }
    public record Response(Frame frame) implements CustomPacketPayload {
        public static final Type<Response> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:builder_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Response> CODEC=FRAMES.map(Response::new,Response::frame);
        public Type<Response> type(){return TYPE;}
    }
    private static BiConsumer<Frame,IPayloadContext> client=(f,c)->{};
    public static void receiver(BiConsumer<Frame,IPayloadContext> value){client=value;}
    public static void register(RegisterPayloadHandlersEvent e){e.registrar("1").executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK).playToServer(Request.TYPE,Request.CODEC,(v,c)->justepicdimensions.network.JEDPayloadWork.enqueue(c,v.frame().text().length()*2+64,()->JEDBuilderGateway.receive(v.frame(),c))).executesOn(net.neoforged.neoforge.network.registration.HandlerThread.MAIN).playToClient(Response.TYPE,Response.CODEC,(v,c)->{if(justepicdimensions.network.JEDPayloads.current(c))client.accept(v.frame(),c);});}
    public static List<Frame> split(UUID session,int request,String text){
        if(text.length()>MAX_CHARS)throw tooLarge(MAX_CHARS);
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_CHARS)throw tooLarge(MAX_CHARS);
        if(bytes.length>=16384){var deflater=new Deflater();try{deflater.setInput(bytes);deflater.finish();var out=new ByteArrayOutputStream();byte[] block=new byte[CHUNK];while(!deflater.finished()){int count=deflater.deflate(block);out.write(block,0,count);}String encoded=COMPRESSED+Base64.getEncoder().encodeToString(out.toByteArray());if(encoded.length()<text.length())text=encoded;}finally{deflater.end();}}
        if(text.length()>MAX_WIRE_CHARS)throw tooLarge(MAX_CHARS);
        var parts=new ArrayList<String>();for(int at=0;at<text.length();){int end=Math.min(text.length(),at+CHUNK);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;parts.add(text.substring(at,end));at=end;}if(parts.isEmpty())parts.add("");
        if(parts.size()>MAX_CHUNKS)throw tooLarge(MAX_CHARS);
        var out=new ArrayList<Frame>();for(int i=0;i<parts.size();i++)out.add(new Frame(session,request,i,parts.size(),parts.get(i)));return out;
    }
    public static com.google.gson.JsonElement value(String text){
        if(text.length()>MAX_CHARS||text.getBytes(StandardCharsets.UTF_8).length>MAX_CHARS)throw tooLarge(MAX_CHARS);
        int depth=0;boolean string=false,escape=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(string){if(escape)escape=false;else if(ch=='\\')escape=true;else if(ch=='"')string=false;}else if(ch=='"')string=true;else if(ch=='{'||ch=='['){if(++depth>64)throw new IllegalArgumentException("Builder JSON nesting exceeds 64");}else if(ch=='}'||ch==']')depth--;}
        return justepicdimensions.definition.JEDJson.read(new java.io.StringReader(text),"builder JSON");
    }
    public static com.google.gson.JsonObject json(String text){return justepicdimensions.definition.JEDJson.object(value(text),"builder message");}
    private static justepicdimensions.definition.JEDDefinitionException tooLarge(int maxBytes){
        int mib=1024*1024;String limit=maxBytes%mib==0?Integer.toString(maxBytes/mib):Double.toString(maxBytes/(double)mib);
        return JEDText.failure("builder.transfer_size",limit);
    }
    private static int wireLimit(int maxBytes){if(maxBytes<1||maxBytes>MAX_CHARS)throw new IllegalArgumentException("Invalid builder transfer budget");return ((maxBytes+65536+2)/3)*4+COMPRESSED.length();}
    public static String decode(String text,int maxBytes){
        if(text.length()>wireLimit(maxBytes))throw tooLarge(maxBytes);
        if(!text.startsWith(COMPRESSED)){if(text.length()>maxBytes||text.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw tooLarge(maxBytes);return text;}
        var inflater=new Inflater();try{
            byte[] packed=Base64.getDecoder().decode(text.substring(COMPRESSED.length()));inflater.setInput(packed);var out=new ByteArrayOutputStream();byte[] block=new byte[CHUNK];
            while(!inflater.finished()){int count=inflater.inflate(block);if(out.size()>maxBytes-count)throw tooLarge(maxBytes);out.write(block,0,count);if(count==0&&!inflater.finished())throw JEDText.failure("builder.transfer_corrupt");}
            if(inflater.getRemaining()!=0)throw JEDText.failure("builder.transfer_corrupt");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }catch(justepicdimensions.definition.JEDDefinitionException error){throw error;}catch(DataFormatException|IllegalArgumentException|CharacterCodingException error){throw JEDText.failure("builder.transfer_corrupt");}finally{inflater.end();}
    }
    /** A full bounded transfer is allowed; small-message flood protection remains separate. */
    public static final class RateLimit {
        private long began=System.nanoTime(),characters;private int messages,frames;private boolean reported;
        public boolean allow(Frame frame,long now,int messageLimit){if(now-began>1_000_000_000L){began=now;characters=0;messages=frames=0;reported=false;}characters+=frame.text().length();frames++;if(frame.index()==0)messages++;return messages<=messageLimit&&frames<=MAX_CHUNKS+messageLimit&&characters<=(long)MAX_WIRE_CHARS+(long)CHUNK*messageLimit;}
        public boolean report(){if(reported)return false;reported=true;return true;}
    }
    /** At most one in-flight message per connection/session, in strict order and with a deadline. */
    public static final class Assembly {
        private final int maxBytes,maxWire;
        private UUID session;private int request,count,next;private long began;private StringBuilder buffer;
        public Assembly(){this(MAX_CHARS);}
        public Assembly(int maxBytes){this.maxBytes=maxBytes;this.maxWire=wireLimit(maxBytes);}
        public String accept(Frame f,long now){String text=acceptWire(f,now);return text==null?null:decode(text,maxBytes);}
        /** Assemble bounded wire text; callers may decode and parse a large message off the game thread. */
        public String acceptWire(Frame f,long now){
            if(buffer!=null&&now-began>10_000_000_000L){clear();throw new IllegalArgumentException("Builder transfer timed out; retry");}
            if(buffer==null){if(f.index()!=0)throw new IllegalArgumentException("Missing first builder frame");if(f.count()>(maxWire+CHUNK-1)/CHUNK)throw tooLarge(maxBytes);session=f.session();request=f.request();count=f.count();next=0;began=now;buffer=new StringBuilder();}
            if(!session.equals(f.session())||request!=f.request()||count!=f.count()||next!=f.index()){clear();throw new IllegalArgumentException("Out-of-order builder transfer; retry");}
            if(buffer.length()>maxWire-f.text().length()){clear();throw tooLarge(maxBytes);}
            buffer.append(f.text());next++;if(next<count)return null;String result=buffer.toString();clear();return result;
        }
        public void clear(){buffer=null;session=null;next=0;}
    }
    private JEDBuilderWire(){}
}
