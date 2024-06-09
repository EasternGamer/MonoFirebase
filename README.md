# MonoFirebase Introduction
A lower-level wrapping of Google Firebase NoSQL for Project Reactor's reactive library.
The main focus is maximizing performance, auto-synchronizing from Firebase, and making it wrapped in Project Reactor.

To achieve better performance, many layers of abstraction and copying between the raw document data and an object were removed.
Most of the low-level code is directly from the Firestore library with minor changes.
A dozen or so chained listeners were removed. To use this library, you will need to make use of the FirestoreObject and FirestoreDataObject. Right now, the FirestoreObject is required to be extended from and represents an actual document. The reason is explained below.
A FirestoreDataObject is an interface which represents any data object in Firestore, such as maps within a document. That way you don't end up with hundreds of thousands of documents in Firestore for sub-objects.

# Structure
## FirestoreObject
Since you can in theory contain multiple database references, right now, it must be an abstract class to keep reference to the database and automatically bind to documents in Firestore. This will likely change to an interface if I can find a clean way to avoid this.
To avoid the costs of reflection and generalizing that may not be desirable, updating, deleting, and syncing is up to the programmer to code.
### Examples
#### Example Object
```java
import com.google.firestore.v1.Value;
import io.github.easterngamer.firebase.FirestoreObject;
import io.github.easterngamer.firebase.MapUtils;

import java.util.Map;

public class ExampleObject implements FirestoreObject {
    // Fields that might be in the document
    private long id;
    private String name;
    private long counter;

    // Getters and setters
    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getCounter() {
        return counter;
    }
    // Updates in Firebase
    public void setName(String name) {
        this.name = name;
        // Indicates that it will be updated in firebase when set.
        updateField("name", this::getName);
    }
    // Updates in Firebase
    public void setCounter(long counter) {
        this.counter = counter;
        // Indicates it will be updated in firebase when set.
        updateField("counter", this::getCounter);
    }
    // This must be made
    @Override
    public void loadFromMap(Map<String, Value> map) {
        this.id = loadId("id", map);
        this.name = loadString("name", map);
        this.counter = loadLong("counter", map);
    }
    // This must be made
    @Override
    public Map<String, Value> getDataMap() {
        return Map.ofEntries(
                MapUtils.entry("id", id),
                MapUtils.entry("name", name),
                MapUtils.entry("counter", counter)
        );
    }
    // This must be made
    @Override
    public String getDocumentReference() {
        return "test/" + id;
    }

    @Override
    public String toString() {
        return "ExampleObject{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", counter=" + counter +
                '}';
    }
}
```
#### Example Loading
```java
import com.google.cloud.firestore.FirestoreOptions;
import io.github.easterngamer.firebase.MonoFirebase;
import reactor.core.publisher.Mono;

public class Main {
    public static void main(String[] args) {
        MonoFirebase monoFirebase = new MonoFirebase(FirestoreOptions.newBuilder().build()); // Replace with relevant settings
        Mono<ExampleObject> exampleObjectMono = monoFirebase.getObject("test/10", ExampleObject::new); // loads the object
        exampleObjectMono
                .doOnNext(exampleObject -> exampleObject.setCounter(exampleObject.getCounter() + 1)) // increment counter
                .subscribe(System.out::println); // Display
    }
}
```
## FirestoreDataObject
This is exclusively for sub-objects that could be added to the document. Such as the below:
(It is loaded identically as in the previous example)
```java
import com.google.firestore.v1.Value;
import io.github.easterngamer.firebase.FirestoreDataObject;
import io.github.easterngamer.firebase.FirestoreObject;
import io.github.easterngamer.firebase.MapUtils;

import java.util.Map;
import java.util.List;

public class ExampleObjectWithSub implements FirestoreObject {
    private long id;
    private String name;
    private List<ExampleSubObject> subObjectList;

    public List<ExampleSubObject> getSubObjectList() {
        return subObjectList;
    }
    
    @Override
    public void loadFromMap(Map<String, Value> map) {
        this.id = loadId("id", map);
        this.name = loadString("name", map);
        this.subObjectList = loadList("subs", map, ExampleSubObject::new);
    }

    @Override
    public Map<String, Value> getDataMap() {
        return Map.ofEntries(
                MapUtils.entry("id", id),
                MapUtils.entry("name", name),
                MapUtils.entry("subs", subObjectList)
        );
    }

    @Override
    public String getDocumentReference() {
        return "testsub/" + id;
    }

    public class ExampleSubObject implements FirestoreDataObject {
        private long id;
        private String subName;
        
        // Having this class as not static, it allows you to do this. Not a requirement.
        public void setSubName(String subName) {
            this.subName = subName;
            updateSubObjectField("subs", Server.this::getSubObjectList);
        }
        
        // If the class was static, this is an example of how you'd need to do it. (Or do it in the parent object)
        public void setSubNameStatic(String subName, ExampleObjectWithSub object) {
            this.subName = subName;
            object.updateSubObjectField("subs", object::getSubObjectList);
        }
        
        @Override
        public void loadFromMap(Map<String, Value> map) {
            this.id = loadId("id", map);
            this.subName = loadString("sub", map);
        }
        
        @Override
        public Map<String, Value> getDataMap() {
            return Map.ofEntries(
                    MapUtils.entry("id", id),
                    MapUtils.entry("sub", subName)
            );
        }
    }
}
```
#### Example Loading
```java
import com.google.cloud.firestore.FirestoreOptions;
import io.github.easterngamer.firebase.MonoFirebase;
import reactor.core.publisher.Mono;

public class Main {
    public static void main(String[] args) {
        MonoFirebase monoFirebase = new MonoFirebase(FirestoreOptions.newBuilder().build()); // Replace with relevant settings
        Mono<ExampleObjectWithSub> exampleObjectMono = monoFirebase.getObject("test/10", ExampleObjectWithSub::new); // loads the object
        exampleObjectMono
                .doOnNext(exampleObject -> {
                    // A little ugly, but it allows us to make use of the actual object's document reference instead of passing it in as an argument.
                    ExampleSubObject subObject = exampleObject.new ExampleSubObject();
                    exampleObject.getSubObjectList().add(subObject);
                    subObject.setSubName("sub"); // Queues updating exampleObjectWithSub's field
                })
                .subscribe(System.out::println); // Display
    }
}
```