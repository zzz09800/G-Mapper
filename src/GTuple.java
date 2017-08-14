import java.io.Serializable;

/**
 * Created by andrew on 7/25/17.
 */
public class GTuple implements Serializable {
    String id;
    String name;

    public GTuple(String id, String name)
    {
        this.id=id;
        this.name=name;
    }
}
