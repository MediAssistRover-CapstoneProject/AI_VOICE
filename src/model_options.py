"""Keep the short voice response budget compatible with current model families."""
import os


def completion_options(model,output_tokens,temperature=.2,effort=None):
    if model.startswith(('gpt-5','gpt-6')):
        reasoning=effort or os.getenv('AI_REASONING_EFFORT','low')
        if model.startswith(('gpt-6-astra','gpt-6.1-sol')) and reasoning in ('none','minimal'):
            raise ValueError('Selected model requires reasoning effort low or higher')
        return dict(reasoning_effort=reasoning,
                    max_completion_tokens=output_tokens+(1024 if reasoning!='none' else 0))
    return dict(temperature=temperature,max_tokens=output_tokens)
