export interface ConfigHelpField {
  name: string;
  description: string;
}

export interface ConfigHelp {
  title: string;
  description: string;
  fields?: ConfigHelpField[];
  minimalExample?: string;
  completeExample?: string;
  commonErrors?: string[];
}
