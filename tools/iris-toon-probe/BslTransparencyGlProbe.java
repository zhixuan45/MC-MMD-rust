import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import com.shiroha.mmdskin.render.material.ToonTransparencyLayersGlProbe;

/** 用实际 BSL profile 复用相交面、深层余量和异常清理参考。 */
public final class BslTransparencyGlProbe {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        var profileMethod = BslBatchGlProbe.class.getDeclaredMethod("profile", ZipFile.class, Map.class, String.class);
        profileMethod.setAccessible(true);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var profile = (ToonOutputProfile) profileMethod.invoke(null, zip,
                    Probe.options(Path.of(path + ".txt")), args[1]);
            if (!profile.isSupported() || profile.lightingSource().isEmpty())
                throw new AssertionError("缺少实际 BSL 输出或照明协议");
            ToonTransparencyLayersGlProbe.profileOverride = profile;
            System.out.println("BSL transparency profile=" + args[1] + " " + profile.id());
            ToonTransparencyLayersGlProbe.main(new String[0]);
        }
    }
}
