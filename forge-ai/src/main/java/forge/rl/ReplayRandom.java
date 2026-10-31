package forge.rl;

import forge.util.MyRandom;
import java.io.*;
import java.util.Base64;
import java.util.Random;

/** Preserves engine randomness when replay skips the AI's random draws. */
final class ReplayRandom {
    private ReplayRandom() { }

    static String save() {
        if (MyRandom.getRandom().getClass() != Random.class) {
            throw new IllegalStateException("Replay requires java.util.Random");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new ObjectOutputStream(bytes)) { out.writeObject(MyRandom.getRandom()); }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static void restore(String encoded) {
        if (encoded.length() > 512) { throw new IllegalArgumentException("Invalid RNG checkpoint"); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            in.setObjectInputFilter(info -> info.depth() > 2 || info.references() > 3 || info.streamBytes() > 384
                    ? ObjectInputFilter.Status.REJECTED : info.serialClass() == null || info.serialClass() == Random.class
                        ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED);
            Object random = in.readObject();
            if (random.getClass() != Random.class || in.read() != -1) {
                throw new IllegalArgumentException("Invalid RNG checkpoint");
            }
            MyRandom.setRandom((Random) random);
        } catch (IOException | ClassNotFoundException e) { throw new IllegalArgumentException("Invalid RNG checkpoint", e); }
    }
}
