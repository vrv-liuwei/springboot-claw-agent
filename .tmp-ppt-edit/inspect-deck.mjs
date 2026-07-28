import fs from "node:fs/promises";
import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const input = process.argv[2];
const output = process.argv[3];

const presentation = await PresentationFile.importPptx(await FileBlob.load(input));
const snapshot = await presentation.inspect({
  kind: "deck,slide,textbox,shape,table,image,chart,layout",
  include: "id,slide,name,title,textPreview,textChars,textLines,bbox,bboxUnit,rows,cols,chartType,alt,isPlaceholder",
  maxChars: 50000,
});

await fs.writeFile(output, snapshot.ndjson, "utf8");
console.log(snapshot.ndjson);
