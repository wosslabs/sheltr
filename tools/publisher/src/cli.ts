import { runPublisher } from './run.ts';

const code = await runPublisher(
  process.argv.slice(2),
  process.cwd(),
  { println: (line) => console.log(line) },
  { println: (line) => console.error(line) },
);
process.exitCode = code;
