export interface AIProvider {
  chat(message: string): Promise<string>;
  // One-shot completion with caller-supplied instructions (no chat persona)
  complete(instructions: string, input: string): Promise<string>;
}
