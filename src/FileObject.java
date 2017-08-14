import java.io.Serializable;

/**
 * Created by andrew on 7/24/17.
 */
public class FileObject implements Serializable {
    String file_name;
    String file_id;
    String file_type; //mime type
    int file_level;
    double size;

    String full_path;

    public FileObject(String name, String id, String type, int level) {
        this.file_name=name;
        this.file_id=id;
        this.file_type=type;
        this.file_level=level;
        this.full_path=null;
    }

    public FileObject(String name, String id, String type, int level, String path) {
        this.file_name=name;
        this.file_id=id;
        this.file_type=type;
        this.file_level=level;
        this.full_path=path;
    }

    public FileObject(){}
}
