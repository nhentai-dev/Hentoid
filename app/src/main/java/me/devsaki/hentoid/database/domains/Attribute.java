package me.devsaki.hentoid.database.domains;

import com.google.gson.annotations.Expose;

import me.devsaki.hentoid.database.contants.AttributeTable;
import me.devsaki.hentoid.database.enums.AttributeType;

/**
 * Created by DevSaki on 09/05/2015.
 */
public class Attribute extends AttributeTable {

    @Expose
    private String url;
    @Expose
    private String name;
    @Expose
    private AttributeType type;

    public Attribute() {
    }

    public Integer getId() {
        return url.hashCode();
    }

    public String getUrl() {
        return url;
    }

    public String getName() {
        return name;
    }

    public AttributeType getType() {
        return type;
    }

    public Attribute setUrl(String url) {
        this.url = url;
        return this;
    }

    public Attribute setName(String name) {
        this.name = name;
        return this;
    }

    public Attribute setType(AttributeType type) {
        this.type = type;
        return this;
    }
}
