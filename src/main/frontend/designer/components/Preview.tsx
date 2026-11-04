import { t } from "../../common/i18n";
import { useEffect, useRef } from "react";
import type { FormLayout } from "../../common/types";
import type { DesignerApi } from "../api";

interface Props {
  api: DesignerApi;
  layout: FormLayout;
  /** Bumped by the parent to re-render the preview with the current layout. */
  nonce: number;
}

/**
 * Shows the real parameter controls with the candidate layout applied. The server renders them inside an iframe,
 * so third-party controls initialise exactly as they do on the build page. The rendered form cannot start a build.
 */
export function Preview({ api, layout, nonce }: Props) {
  const ref = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    const frame = ref.current;
    if (frame) {
      api.renderPreview(frame, layout);
    }
    // layout is intentionally not a dependency: the preview refreshes when nonce changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nonce]);

  return (
    <div className="bpe-preview-pane">
      <iframe ref={ref} name="bpe-preview-frame" title={t("designer.previewFrame")} className="bpe-preview-frame" />
    </div>
  );
}
