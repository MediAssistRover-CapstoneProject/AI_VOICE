package com.mediassistrover.backend;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.mapping.MappingArchiveReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

final class MappingTestArchive {
    static final byte[] YAML="image: map.pgm\nresolution: 0.05\norigin: [0, 0, 0]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: 0.196\n".getBytes(StandardCharsets.UTF_8);
    static final byte[] PGM=new byte[]{'P','5','\n','2',' ','2','\n','2','5','5','\n',0,10,32,(byte)255};
    static ObjectNode manifest(ObjectMapper json,UUID id,byte[] yaml,byte[] pgm) {
        try{var bytes=new ByteArrayOutputStream();bytes.write(yaml);bytes.write(0);bytes.write(pgm);
            return json.createObjectNode().put("artifact_id",id.toString()).put("asset_hash",MappingArchiveReader.hash(bytes.toByteArray()))
                    .put("width_px",2).put("height_px",2).put("resolution_m_per_pixel",.05).put("width_m",.1).put("height_m",.1)
                    .put("origin_x_m",0).put("origin_y_m",0).put("origin_yaw_rad",0).put("frame_id","map");
        }catch(IOException ex){throw new AssertionError(ex);}
    }
    static byte[] archive(ObjectMapper json,ObjectNode manifest) {return archive(YAML,PGM,bytes(json,manifest),"map.yaml",'0',false);}
    static byte[] bytes(ObjectMapper json,ObjectNode n){try{return json.writeValueAsBytes(n);}catch(IOException ex){throw new AssertionError(ex);}}
    static byte[] archive(byte[] yaml,byte[] pgm,byte[] manifest,String yamlName,char type,boolean duplicate) {
        try{var tar=new ByteArrayOutputStream();entry(tar,yamlName,yaml,type);entry(tar,"map.pgm",pgm,'0');entry(tar,"manifest.json",manifest,'0');if(duplicate)entry(tar,"map.yaml",yaml,'0');tar.write(new byte[1024]);
            var gz=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(gz)){gzip.write(tar.toByteArray());}return gz.toByteArray();
        }catch(IOException ex){throw new AssertionError(ex);}
    }
    private static void entry(ByteArrayOutputStream tar,String name,byte[] data,char type) throws IOException {
        byte[] header=new byte[512];field(header,0,name);field(header,100,"0000644");field(header,108,"0000000");field(header,116,"0000000");
        field(header,124,String.format("%011o",data.length));field(header,136,"00000000000");Arrays.fill(header,148,156,(byte)' ');header[156]=(byte)type;field(header,257,"ustar");
        long sum=0;for(byte b:header)sum+=Byte.toUnsignedInt(b);field(header,148,String.format("%06o",sum));header[154]=0;header[155]=' ';
        tar.write(header);tar.write(data);tar.write(new byte[(512-data.length%512)%512]);
    }
    private static void field(byte[] header,int at,String value){byte[] text=value.getBytes(StandardCharsets.US_ASCII);System.arraycopy(text,0,header,at,text.length);}
}
