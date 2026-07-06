package vn.vnpt.util.common.mail;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.vnpt.util.annotation.SpecialSymbolConstraint;

import java.io.File;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailAttachment {

    /**
     * True : Inline image<br>
     * False : File attachment
     */
    private boolean inline;

    @SpecialSymbolConstraint
    private String inlineId;

    @SpecialSymbolConstraint
    private String mimeType;

    private File file;

    /**
     * Delete attachment after email successfully sent<br>
     * Default True
     */
    private boolean deleteAfter = true;

}
