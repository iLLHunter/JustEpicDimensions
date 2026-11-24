package justepicdimensions.bundle;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import justepicdimensions.definition.*;

/** A bounded, versioned data/resource pack archive. Reading never extracts files. */
public final class JEDDimensionBundle {
    public static final String MANIFEST="jed_bundle.json";
    public static final int MAX_FILES=4096,MAX_FILE=32*1024*1024,MAX_BYTES=128*1024*1024;
    public record Bundle(JsonObject manifest,Map<String,byte[]> files){
        public Bundle {manifest=manifest.deepCopy();var copy=new TreeMap<String,byte[]>();files.forEach((p,b)->copy.put(p,b.clone()));files=Collections.unmodifiableMap(copy);}
        @Override public JsonObject manifest(){return manifest.deepCopy();}
        @Override public Map<String,byte[]> files(){var copy=new TreeMap<String,byte[]>();files.forEach((p,b)->copy.put(p,b.clone()));return Collections.unmodifiableMap(copy);}
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static void path(String path){
        if(path.length()>512||!path.matches("(?:data|assets)/[a-z0-9_.-]+/[a-z0-9_./-]+")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")||p.endsWith(".")||p.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:[.].*)?")))throw new JEDDefinitionException("Unsafe bundle path: "+path);
        if(path.startsWith("assets/")&&!path.matches("assets/[a-z0-9_.-]+/(?:textures/[a-z0-9_./-]+[.]png(?:[.]mcmeta)?|lang/[a-z0-9_./-]+[.]json)"))throw new JEDDefinitionException("Unsupported client asset: "+path);
        if(path.startsWith("data/")&&!path.endsWith(".json")&&!path.matches("data/[a-z0-9_.-]+/(?:structure/[a-z0-9_./-]+[.]nbt|justepicdimensions/schematics/[a-z0-9_./-]+[.]schem)"))throw new JEDDefinitionException("Unsupported data asset: "+path);
    }
    public static void paths(Collection<String> paths){var seen=new HashSet<>(paths);for(String value:paths){if(value.equals("pack.mcmeta"))continue;path(value);int end=value.lastIndexOf('/');while(end>0){if(seen.contains(value.substring(0,end)))throw new JEDDefinitionException("Bundle file is also a parent directory: "+value);end=value.lastIndexOf('/',end-1);}}}
    public static JsonElement readJson(byte[] bytes,String name){
        final String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException error){throw new JEDDefinitionException("Invalid UTF-8 in "+name,error);}int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>64)throw new JEDDefinitionException("Bundle JSON nesting exceeds 64 in "+name);}else if(c=='}'||c==']')depth--;}
        return JEDJson.read(new StringReader(text),name);
    }
    public static Bundle create(String mode,JsonArray dimensions,JsonArray requirements,Map<String,byte[]> files,int dataFormat,int resourceFormat){
        if(!Set.of("linked","resolved").contains(mode)||dimensions.isEmpty())throw new JEDDefinitionException("Bundle requires dimensions and a linked/resolved mode");
        var payload=new TreeMap<>(files);var pack=new JsonObject();var meta=new JsonObject();meta.addProperty("pack_format",resourceFormat);meta.addProperty("description","Just Epic Dimensions bundle client assets");pack.add("pack",meta);payload.put("pack.mcmeta",json(pack));
        var manifest=new JsonObject();manifest.addProperty("format_version",1);manifest.addProperty("mode",mode);manifest.addProperty("minecraft","1.21.1");manifest.addProperty("data_pack_format",dataFormat);manifest.addProperty("resource_pack_format",resourceFormat);manifest.add("dimensions",dimensions.deepCopy());manifest.add("requirements",requirements.deepCopy());var entries=new JsonObject();payload.forEach((name,bytes)->{if(!name.equals("pack.mcmeta"))path(name);if(bytes.length>MAX_FILE)throw new JEDDefinitionException("Bundle file too large: "+name);var item=new JsonObject();item.addProperty("size",bytes.length);item.addProperty("sha256",hash(bytes));entries.add(name,item);});manifest.add("files",entries);var result=new Bundle(manifest,payload);validate(result);return result;
    }
    public static byte[] json(JsonElement json){return (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)+"\n").getBytes(StandardCharsets.UTF_8);}
    public static void validate(Bundle bundle){
        var manifest=bundle.manifest;JEDJson.keys(manifest,Set.of("format_version","mode","minecraft","data_pack_format","resource_pack_format","dimensions","requirements","files"),"bundle manifest");
        if(JEDJson.integer(manifest.get("format_version"),"bundle version")!=1||!JEDJson.string(manifest.get("minecraft"),"bundle Minecraft").equals("1.21.1"))throw new JEDDefinitionException("Unsupported bundle or Minecraft version");
        if(!Set.of("linked","resolved").contains(JEDJson.string(manifest.get("mode"),"bundle mode")))throw new JEDDefinitionException("Invalid bundle mode");for(String key:List.of("data_pack_format","resource_pack_format"))if(JEDJson.integer(manifest.get(key),key)<=0)throw new JEDDefinitionException("Invalid pack format");
        var dimensions=manifest.get("dimensions");if(dimensions==null||!dimensions.isJsonArray()||dimensions.getAsJsonArray().isEmpty())throw new JEDDefinitionException("Missing bundle dimensions");var seen=new HashSet<String>();for(var raw:dimensions.getAsJsonArray()){String id=JEDJson.string(raw,"bundle dimension");JEDDimensionDefinition.id(id,"bundle dimension");if(!seen.add(id))throw new JEDDefinitionException("Repeated bundle dimension "+id);String[] bits=id.split(":",2);if(!bundle.files.containsKey("data/"+bits[0]+"/dimension/"+bits[1]+".json"))throw new JEDDefinitionException("Missing bundled dimension definition "+id);}
        if(!manifest.has("requirements")||!manifest.get("requirements").isJsonArray()||manifest.getAsJsonArray("requirements").size()>MAX_FILES)throw new JEDDefinitionException("Bundle requirements must be an array");for(var raw:manifest.getAsJsonArray("requirements")){var requirement=JEDJson.object(raw,"bundle requirement");JEDJson.keys(requirement,Set.of("kind","id"),"bundle requirement");if(!Set.of("mod","resource","client_asset","reference").contains(JEDJson.string(requirement.get("kind"),"requirement kind")))throw new JEDDefinitionException("Unknown requirement kind");JEDJson.string(requirement.get("id"),"requirement ID");}
        var entries=JEDJson.object(manifest.get("files"),"bundle files");if(entries.size()>MAX_FILES||!entries.keySet().equals(bundle.files.keySet())||!entries.has("pack.mcmeta"))throw new JEDDefinitionException("Bundle file inventory does not match its manifest");long total=0;paths(bundle.files.keySet());
        for(var entry:bundle.files.entrySet()){
            String name=entry.getKey();if(!name.equals("pack.mcmeta"))path(name);var bytes=entry.getValue();if(bytes.length>MAX_FILE||(total+=bytes.length)>MAX_BYTES)throw new JEDDefinitionException("Bundle exceeds its size limit");var metadata=JEDJson.object(entries.get(name),name);JEDJson.keys(metadata,Set.of("size","sha256"),name);if(JEDJson.integer(metadata.get("size"),name)!=bytes.length||!hash(bytes).equals(JEDJson.string(metadata.get("sha256"),name)))throw new JEDDefinitionException("Bundle checksum mismatch: "+name);
            if(name.endsWith(".json")||name.endsWith(".mcmeta")){var decoded=readJson(bytes,name);if(name.endsWith(".mcmeta"))JEDJson.object(decoded,name);}
        }
    }
    public static Bundle read(Path file){
        var entries=new TreeMap<String,byte[]>();var names=new HashSet<String>();long total=0;int count=0;JsonObject manifest=null;
        try(var input=new ZipInputStream(Files.newInputStream(file),StandardCharsets.UTF_8)){
            for(ZipEntry entry;(entry=input.getNextEntry())!=null;){if(++count>MAX_FILES*2+32)throw new JEDDefinitionException("Too many bundle entries");String name=entry.getName();if(entry.isDirectory()){if(name.length()>512||!name.matches("(?:data|assets)(?:/[a-z0-9_.-]+)*/")||Arrays.stream(name.split("/")).anyMatch(p->p.equals(".")||p.equals("..")))throw new JEDDefinitionException("Unsafe bundle directory "+name);continue;}if(!name.equals(MANIFEST)&&!name.equals("pack.mcmeta"))path(name);if(!names.add(name.toLowerCase(Locale.ROOT)))throw new JEDDefinitionException("Duplicate bundle entry "+name);
                var output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int length;(length=input.read(buffer))!=-1;){if(output.size()+length>MAX_FILE||(total+=length)>MAX_BYTES)throw new JEDDefinitionException("Bundle exceeds its size limit");output.write(buffer,0,length);}byte[] bytes=output.toByteArray();if(name.equals(MANIFEST))manifest=JEDJson.object(readJson(bytes,MANIFEST),MANIFEST);else entries.put(name,bytes);
            }
        }catch(IOException error){throw new JEDDefinitionException("Cannot read dimension bundle "+file,error);}if(manifest==null)throw new JEDDefinitionException("Not a JED dimension bundle: missing "+MANIFEST);var bundle=new Bundle(manifest,entries);validate(bundle);return bundle;
    }
    /** Hex colours such as "#aa2200" are not tag references; anything else must name a valid tag ID after the "#". */
    public static boolean tagReference(String text){return text.startsWith("#")&&!text.matches("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")&&net.minecraft.resources.ResourceLocation.tryParse(text.substring(1))!=null;}
    /** Exports never replace an earlier archive. */
    public static void requireNew(Path file){if(Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new JEDDefinitionException("Bundle "+file.getFileName()+" already exists in "+file.toAbsolutePath().getParent()+". Choose another name or delete the old file.");}
    public static void write(Path file,Bundle bundle){
        validate(bundle);requireNew(file);Path temporary=null;try{Files.createDirectories(file.toAbsolutePath().getParent());temporary=Files.createTempFile(file.toAbsolutePath().getParent(),".jed-bundle-",".zip");try(var output=new ZipOutputStream(Files.newOutputStream(temporary),StandardCharsets.UTF_8)){var files=new TreeMap<>(bundle.files);files.put(MANIFEST,json(bundle.manifest));for(var entry:files.entrySet()){var zip=new ZipEntry(entry.getKey());zip.setTime(0);output.putNextEntry(zip);output.write(entry.getValue());output.closeEntry();}}Files.move(temporary,file);temporary=null;}catch(java.nio.file.FileAlreadyExistsException error){requireNew(file);throw new JEDDefinitionException("Cannot write bundle "+file+": it already exists",error);}catch(IOException error){throw new JEDDefinitionException("Cannot write bundle "+file+": "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()),error);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private JEDDimensionBundle(){}
}
