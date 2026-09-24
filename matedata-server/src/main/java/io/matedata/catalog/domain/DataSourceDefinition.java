package io.matedata.catalog.domain;
public record DataSourceDefinition(String id,String name,String type,String jdbcUrl,String username,String encryptedPassword,String status,String createdAt) {
    public record View(String id,String name,String type,String jdbcUrl,String username,String status,String createdAt){}
    public View view(){return new View(id,name,type,jdbcUrl,username,status,createdAt);}
}
