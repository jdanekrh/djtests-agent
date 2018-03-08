import java.lang.reflect.Field;

public class ProcessManagement {
    static int getPid(Process process) {
        final Field field;
        try {
            field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(process);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Could not get a PID", e);
        }
    }
}
